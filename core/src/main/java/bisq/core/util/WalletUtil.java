package bisq.core.util;

import bisq.core.app.BisqEnvironment;
import bisq.core.btc.setup.BisqKeyChainFactory;

import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletExtension;
import org.bitcoinj.wallet.WalletProtobufSerializer;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import java.util.List;

public class WalletUtil {
    public static Wallet readWallet (File walletFile) throws IOException, UnreadableWalletException {
        Wallet wallet;
        try (FileInputStream walletStream = new FileInputStream(walletFile)) {
            List<WalletExtension> extensions = ImmutableList.of();
            WalletExtension[] extArray = extensions.toArray(new WalletExtension[extensions.size()]);
            Protos.Wallet proto = WalletProtobufSerializer.parseToProto(walletStream);
            final WalletProtobufSerializer serializer = new WalletProtobufSerializer();

            serializer.setKeyChainFactory(new BisqKeyChainFactory(true));
            wallet = serializer.readWallet(BisqEnvironment.getBaseCurrencyNetwork().getParameters(), extArray, proto);
        }
        return wallet;
    }
}
