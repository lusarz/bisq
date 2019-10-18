package bisq.core.util;

import bisq.core.app.BisqEnvironment;
import bisq.core.btc.BaseCurrencyNetwork;
import bisq.core.util.coin.ImmutableCoinFormatter;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;

import java.nio.file.Paths;

import java.io.File;
import java.io.IOException;

public class PrintUtil {
  public static void printEverything () throws IOException, UnreadableWalletException {
      String userDataDir = BisqEnvironment.DEFAULT_USER_DATA_DIR;
      String appName = BisqEnvironment.DEFAULT_APP_NAME;
      String appDataDir = BisqEnvironment.appDataDir(userDataDir, appName);
      BaseCurrencyNetwork baseCurrencyNetwork = BisqEnvironment.getBaseCurrencyNetwork();

      String btcNetworkDir = Paths.get(appDataDir, baseCurrencyNetwork.name().toLowerCase()).toString();

      File walletDir = new File(btcNetworkDir, "wallet");
      printProperty("walletDir", walletDir.getAbsolutePath());

      String btcWalletFileName = "bisq_" + BisqEnvironment.getBaseCurrencyNetwork().getCurrencyCode() + ".wallet";
      System.out.println(btcWalletFileName);

      Wallet wallet = WalletUtil.readWallet(new File(walletDir, btcWalletFileName));

      ImmutableCoinFormatter formatter = new ImmutableCoinFormatter(MonetaryFormat.BTC);
      Coin totalReceived = wallet.getTotalReceived();
      printProperty("totalReceived", formatter.formatCoinWithCode(totalReceived));
  }

    private static void printProperty (String key, String value) {
        System.out.println(key + ": " + value);
    }
}
