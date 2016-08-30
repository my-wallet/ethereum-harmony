package com.ethercamp.harmony.service;

import com.ethercamp.harmony.dto.WalletAddressDTO;
import com.ethercamp.harmony.dto.WalletConfirmTransactionDTO;
import com.ethercamp.harmony.dto.WalletInfoDTO;
import com.ethercamp.harmony.keystore.Keystore;
import com.ethercamp.harmony.service.wallet.FileSystemWalletStore;
import com.ethercamp.harmony.service.wallet.WalletAddressItem;
import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.ethereum.core.*;
import org.ethereum.crypto.ECKey;
import org.ethereum.db.ByteArrayWrapper;
import org.ethereum.facade.Ethereum;
import org.ethereum.listener.EthereumListenerAdapter;
import org.ethereum.util.ByteUtil;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by Stan Reshetnyk on 24.08.16.
 */
@Service
@Slf4j(topic = "harmony")
public class WalletService {

    private static final BigInteger gasLimit = BigInteger.valueOf(21_000L);

    @Autowired
    Ethereum ethereum;

    @Autowired
    Repository repository;

    @Autowired
    FileSystemWalletStore fileSystemWalletStore;

    @Autowired
    ClientMessageService clientMessageService;

    final Map<String, String> addresses = new HashMap<>();

    @Autowired
    Keystore keystore;

    final Map<String, TransactionInfo> pendingSendTransactions = new ConcurrentHashMap<>();
    final Map<String, TransactionInfo> pendingReceiveTransactions = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        addresses.clear();

        AtomicInteger index = new AtomicInteger();
        Arrays.asList(keystore.listStoredKeys())
                .forEach(a -> addresses.put(remove0x(a), "Account " + index.incrementAndGet()));

        fileSystemWalletStore.fromStore().stream()
                .forEach(a -> addresses.put(a.address, a.name));

        ethereum.addListener(new EthereumListenerAdapter() {
            @Override
            public void onPendingTransactionsReceived(List<Transaction> list) {
                handlePendingTransactionsReceived(list);
            }

            @Override
            public void onBlock(BlockSummary blockSummary) {
                handleBlock(blockSummary);
            }
        });
    }

    public void handleBlock(BlockSummary blockSummary) {
        checkForChangesInWallet(blockSummary
                        .getReceipts().stream()
                        .map(receipt -> receipt.getTransaction())
                        .collect(Collectors.toList()),
                (info) -> {
                    pendingSendTransactions.remove(info.getHash());
                    clientMessageService.sendToTopic("/topic/confirmTransaction", new WalletConfirmTransactionDTO(
                            info.getHash(),
                            info.getAmount(),
                            info.getSending()
                    ));
                },
                (info) -> pendingReceiveTransactions.remove(info.getHash()));
    }

    public void handlePendingTransactionsReceived(List<Transaction> list) {
        checkForChangesInWallet(list,
                (info) -> pendingSendTransactions.put(info.getHash(), info),
                (info) -> pendingReceiveTransactions.put(info.getHash(), info));
    }

    private void checkForChangesInWallet(List<Transaction> transactions, Consumer<TransactionInfo> sendHandler, Consumer<TransactionInfo> receiveHandler) {
        final Set<ByteArrayWrapper> subscribed = addresses.keySet().stream()
                .map(a -> remove0x(a))
                .flatMap(a -> {
                    try {
                        return Stream.of(new ByteArrayWrapper(Hex.decode(a)));
                    } catch (Exception e) {
                        log.error("Problem getting bytes representation from " + a);
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toSet());

        final List<Transaction> confirmedTransactions = transactions.stream()
                .filter(transaction ->
                        setContains(subscribed, transaction.getReceiveAddress())
                            || setContains(subscribed, transaction.getSender()))
                .collect(Collectors.toList());

        confirmedTransactions.forEach(transaction -> {
            final String hash = toHexString(transaction.getHash());
            final BigInteger amount = ByteUtil.bytesToBigInteger(transaction.getValue());
            final boolean hasSender = setContains(subscribed, transaction.getSender());
            final boolean hasReceiver = setContains(subscribed, transaction.getReceiveAddress());
            log.debug("Handle transaction hash:" + hash + ", hasSender:" + hasSender + ", amount:" + amount);

            if (hasSender) {
                sendHandler.accept(new TransactionInfo(hash, amount, hasSender, toHexString(transaction.getSender())));
            }
            if (hasReceiver) {
                receiveHandler.accept(new TransactionInfo(hash, amount, hasSender, toHexString(transaction.getReceiveAddress())));
            }
        });

        if (!confirmedTransactions.isEmpty()) {
            // update wallet if transactions are related to wallet addresses
            clientMessageService.sendToTopic("/topic/getWalletInfo", getWalletInfo());
        }
    }


    private String remove0x(String input) {
        if (input != null && input.startsWith("0x")) {
            return input.substring(2);
        }
        return input;
    }

    public WalletInfoDTO getWalletInfo() {
        BigInteger gasPrice = BigInteger.valueOf(ethereum.getGasPrice());
        BigInteger txFee = gasLimit.multiply(gasPrice);

//        log.info("getWalletInfo");
        List<WalletAddressDTO> list = addresses.entrySet().stream()
                .flatMap(e -> {
                    try {
                        final String hexAddress = e.getKey();
                        final byte[] address = Hex.decode(hexAddress);
                        final BigInteger balance = repository.getBalance(address);
                        final BigInteger sendBalance = calculatePendingChange(pendingSendTransactions, hexAddress, txFee);
                        final BigInteger receiveBalance = calculatePendingChange(pendingReceiveTransactions, hexAddress, BigInteger.ZERO);
//                        log.info("B " + hexAddress + " " + balance + " " + sendBalance + " " + receiveBalance);

                        return Stream.of(new WalletAddressDTO(
                                e.getValue(),
                                e.getKey(),
                                balance,
                                receiveBalance.subtract(sendBalance),
                                keystore.hasStoredKey(e.getKey())));
                    } catch (Exception exception) {
                        log.error("Error in making wallet address", exception);
                        return Stream.empty();
                    }
                })
                .collect(Collectors.toList());

        BigInteger totalAmount = list.stream()
                .map(t -> t.getAmount())
                .reduce(BigInteger.ZERO, (state, amount) -> state.add(amount));

        WalletInfoDTO result = new WalletInfoDTO(totalAmount);

        result.getAddresses().addAll(list);
        return result;
    }

    private BigInteger calculatePendingChange(Map<String, TransactionInfo> transactions, String hexAddress, BigInteger txFee) {
        return transactions.values().stream()
                .filter(info -> info.getAddress().equals(hexAddress))
                .map(info -> info.getAmount())
                .reduce(BigInteger.ZERO, (state, amount) -> state.add(amount).add(txFee));
    }

    public String newAddress(String name, String password) {
        log.info("newAddress " + name);
        // generate new private key
        final ECKey key = new ECKey();
        final Account account = new Account();
        account.init(key);
        final String address = toHexString(account.getAddress());

        keystore.storeKey(key, password);
        addresses.put(address, name);

        flushWalletToDisk();

        clientMessageService.sendToTopic("/topic/getWalletInfo", getWalletInfo());

        return address;
    }

    public String importAddress(String address, String name) {
        validateAddress(address);

        addresses.put(address, name);

        flushWalletToDisk();

        clientMessageService.sendToTopic("/topic/getWalletInfo", getWalletInfo());

        return address;
    }

    private void validateAddress(String value) {
        Objects.requireNonNull(value);
        if (value.length() != 40) {
            throw new RuntimeException("Address value is invalid");
        }
        Hex.decode(value);
    }

    public void removeAddress(String address) {
        addresses.remove(address);
        keystore.removeKey(address);

        flushWalletToDisk();

        clientMessageService.sendToTopic("/topic/getWalletInfo", getWalletInfo());
    }

    private String toHexString(byte[] value) {
        return value == null ? "" : Hex.toHexString(value);
    }

    private boolean setContains(Set<ByteArrayWrapper> set, byte[] value) {
        return value != null && set.contains(new ByteArrayWrapper(value));
    }

    private void flushWalletToDisk() {
        fileSystemWalletStore.toStore(addresses.entrySet().stream()
                .map(e -> new WalletAddressItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList()));
    }

    @Value
    @AllArgsConstructor
    public class TransactionInfo {

        private String hash;

        private BigInteger amount;

        private Boolean sending;

        private String address;
    }
}
