package io.bitsquare.msg;

import com.google.inject.Inject;
import io.bitsquare.BitSquare;
import io.bitsquare.msg.listeners.*;
import io.bitsquare.trade.Offer;
import io.bitsquare.trade.payment.offerer.OffererAsBuyerProtocol;
import io.bitsquare.trade.payment.offerer.messages.*;
import io.bitsquare.trade.payment.taker.TakerAsSellerProtocol;
import io.bitsquare.trade.payment.taker.listeners.GetPeerAddressListener;
import io.bitsquare.trade.payment.taker.messages.PayoutTxPublishedMessage;
import io.bitsquare.trade.payment.taker.messages.RequestOffererPublishDepositTxMessage;
import io.bitsquare.trade.payment.taker.messages.RequestTakeOfferMessage;
import io.bitsquare.trade.payment.taker.messages.TakeOfferFeePayedMessage;
import io.bitsquare.user.Arbitrator;
import io.bitsquare.util.DSAKeyUtil;
import io.bitsquare.util.FileUtil;
import java.io.IOException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.*;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import net.tomp2p.connection.Bindings;
import net.tomp2p.connection.PeerConnection;
import net.tomp2p.futures.*;
import net.tomp2p.p2p.Peer;
import net.tomp2p.p2p.PeerMaker;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.rpc.ObjectDataReply;
import net.tomp2p.storage.Data;
import net.tomp2p.storage.StorageDisk;
import net.tomp2p.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * That facade delivers messaging functionality from the TomP2P library
 * The TomP2P library codebase shall not be used outside that facade.
 * That way a change of the library will only affect that class.
 */
@SuppressWarnings({"EmptyMethod", "ConstantConditions"})
public class MessageFacade
{
    private static final String PING = "ping";
    private static final String PONG = "pong";
    private static final Logger log = LoggerFactory.getLogger(MessageFacade.class);
    private static final int MASTER_PEER_PORT = 5000;

    private final List<OrderBookListener> orderBookListeners = new ArrayList<>();
    private final List<TakeOfferRequestListener> takeOfferRequestListeners = new ArrayList<>();
    private final List<ArbitratorListener> arbitratorListeners = new ArrayList<>();

    private final Map<String, TakerAsSellerProtocol> takerPaymentProtocols = new HashMap<>();
    private final Map<String, OffererAsBuyerProtocol> offererAsBuyerProtocols = new HashMap<>();

    private final List<PingPeerListener> pingPeerListeners = new ArrayList<>();
    private final BooleanProperty isDirty = new SimpleBooleanProperty(false);
    private Peer myPeer;

    private KeyPair keyPair;
    private Long lastTimeStamp = -3L;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public MessageFacade()
    {
       /* try
        {
            masterPeer = BootstrapMasterPeer.GET_INSTANCE(MASTER_PEER_PORT);
        } catch (Exception e)
        {
            if (masterPeer != null)
                masterPeer.shutdown();
            System.err.println("masterPeer already instantiated by another app. " + e.getMessage());
        }  */
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public Methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void init()
    {
        int port = Bindings.MAX_PORT - Math.abs(new Random().nextInt()) % (Bindings.MAX_PORT - Bindings.MIN_DYN_PORT);
        if (BitSquare.ID.contains("taker"))
            port = 4501;
        else if (BitSquare.ID.contains("offerer"))
            port = 4500;

        try
        {
            createMyPeerInstance(port);
            // setupStorage();
            //TODO save periodically or get informed if network address changes
            saveMyAddressToDHT();
            setupReplyHandler();
        } catch (IOException e)
        {
            shutDown();
            log.error("Error at init myPeerInstance" + e.getMessage());
        }
    }

    public void shutDown()
    {
        if (myPeer != null)
            myPeer.shutdown();
    }


    public void setupReputationRoot() throws IOException
    {
        String pubKeyAsHex = DSAKeyUtil.getHexStringFromPublicKey(getPubKey());  // out message ID
        final Number160 locationKey = Number160.createHash("REPUTATION_" + pubKeyAsHex); // out reputation root storage location
        final Number160 contentKey = Utils.makeSHAHash(getPubKey().getEncoded());  // my pubKey -> i may only put in 1 reputation
        final Data reputationData = new Data(Number160.ZERO).setProtectedEntry().setPublicKey(getPubKey()); // at registration time we add a null value as data
        // we use a pubkey where we provable cannot own the private key.
        // the domain key must be verifiable by peers to be sure the reputation root was net deleted by the owner.
        // so we use the locationKey as it already meets our requirements (verifiable and impossible to create a private key out of it)
        myPeer.put(locationKey).setData(contentKey, reputationData).setDomainKey(locationKey).setProtectDomain().start();
    }

    public void addReputation(String pubKeyAsHex) throws IOException
    {
        final Number160 locationKey = Number160.createHash("REPUTATION_" + pubKeyAsHex);  // reputation root storage location ot the peer
        final Number160 contentKey = Utils.makeSHAHash(getPubKey().getEncoded());  // my pubKey -> i may only put in 1 reputation, I may update it later. eg. counter for 5 trades...
        final Data reputationData = new Data("TODO: some reputation data..., content signed and sig attached").setProtectedEntry().setPublicKey(getPubKey());
        myPeer.put(locationKey).setData(contentKey, reputationData).start();
    }

    // At any offer or take offer fee payment the trader add the tx id and the pubKey and the signature of that tx to that entry.
    // That way he can prove with the signature that he is the payer of the offer fee.
    // It does not assure that the trade was really executed, but we can protect the traders privacy that way.
    // If we use the trade, we would link all trades together and would reveal the whole trading history.
    @SuppressWarnings("UnusedParameters")
    public void addOfferFeePaymentToReputation(String txId, String pubKeyOfFeePayment) throws IOException
    {
        String pubKeyAsHex = DSAKeyUtil.getHexStringFromPublicKey(getPubKey());  // out message ID
        final Number160 locationKey = Number160.createHash("REPUTATION_" + pubKeyAsHex);  // reputation root storage location ot the peer
        final Number160 contentKey = Utils.makeSHAHash(getPubKey().getEncoded());  // my pubKey -> i may only put in 1 reputation, I may update it later. eg. counter for 5 trades...
        final Data reputationData = new Data("TODO: tx, btc_pubKey, sig(tx), content signed and sig attached").setProtectedEntry().setPublicKey(getPubKey());
        myPeer.put(locationKey).setData(contentKey, reputationData).start();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Arbitrators
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addArbitrator(Arbitrator arbitrator) throws IOException
    {
        Number160 locationKey = Number160.createHash("Arbitrators");
        final Number160 contentKey = Number160.createHash(arbitrator.getId());
        final Data arbitratorData = new Data(arbitrator);
        //offerData.setTTLSeconds(5);
        final FutureDHT addFuture = myPeer.put(locationKey).setData(contentKey, arbitratorData).start();
        //final FutureDHT addFuture = myPeer.add(locationKey).setData(offerData).start();
        addFuture.addListener(new BaseFutureAdapter<BaseFuture>()
        {
            @Override
            public void operationComplete(BaseFuture future) throws Exception
            {
                Platform.runLater(() -> onArbitratorAdded(arbitratorData, future.isSuccess(), locationKey));
            }
        });
    }

    @SuppressWarnings("UnusedParameters")
    private void onArbitratorAdded(Data arbitratorData, boolean success, Number160 locationKey)
    {
    }


    @SuppressWarnings("UnusedParameters")
    public void getArbitrators(Locale languageLocale)
    {
        final Number160 locationKey = Number160.createHash("Arbitrators");
        final FutureDHT getArbitratorsFuture = myPeer.get(locationKey).setAll().start();
        getArbitratorsFuture.addListener(new BaseFutureAdapter<BaseFuture>()
        {
            @Override
            public void operationComplete(BaseFuture future) throws Exception
            {
                final Map<Number160, Data> dataMap = getArbitratorsFuture.getDataMap();
                Platform.runLater(() -> onArbitratorsReceived(dataMap, future.isSuccess()));
            }
        });
    }

    private void onArbitratorsReceived(Map<Number160, Data> dataMap, boolean success)
    {
        for (ArbitratorListener arbitratorListener : arbitratorListeners)
            arbitratorListener.onArbitratorsReceived(dataMap, success);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Publish offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    //TODO use Offer and do proper serialisation here
    public void addOffer(Offer offer) throws IOException
    {
        Number160 locationKey = Number160.createHash(offer.getCurrency().getCurrencyCode());
        final Number160 contentKey = Number160.createHash(offer.getId());
        final Data offerData = new Data(offer);
        //offerData.setTTLSeconds(5);
        final FutureDHT addFuture = myPeer.put(locationKey).setData(contentKey, offerData).start();
        //final FutureDHT addFuture = myPeer.add(locationKey).setData(offerData).start();
        addFuture.addListener(new BaseFutureAdapter<BaseFuture>()
        {
            @Override
            public void operationComplete(BaseFuture future) throws Exception
            {
                Platform.runLater(() -> onOfferAdded(offerData, future.isSuccess(), locationKey));
            }
        });
    }

    private void onOfferAdded(Data offerData, boolean success, Number160 locationKey)
    {
        setDirty(locationKey);

        for (OrderBookListener orderBookListener : orderBookListeners)
            orderBookListener.onOfferAdded(offerData, success);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Get offers
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void getOffers(String currency)
    {
        final Number160 locationKey = Number160.createHash(currency);
        final FutureDHT getOffersFuture = myPeer.get(locationKey).setAll().start();
        getOffersFuture.addListener(new BaseFutureAdapter<BaseFuture>()
        {
            @Override
            public void operationComplete(BaseFuture future) throws Exception
            {
                final Map<Number160, Data> dataMap = getOffersFuture.getDataMap();
                Platform.runLater(() -> onOffersReceived(dataMap, future.isSuccess()));
            }
        });
    }

    private void onOffersReceived(Map<Number160, Data> dataMap, boolean success)
    {
        for (OrderBookListener orderBookListener : orderBookListeners)
            orderBookListener.onOffersReceived(dataMap, success);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Remove offer
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void removeOffer(Offer offer)
    {
        Number160 locationKey = Number160.createHash(offer.getCurrency().getCurrencyCode());
        Number160 contentKey = Number160.createHash(offer.getId());
        log.debug("removeOffer");
        FutureDHT removeFuture = myPeer.remove(locationKey).setReturnResults().setContentKey(contentKey).start();
        removeFuture.addListener(new BaseFutureAdapter<BaseFuture>()
        {
            @Override
            public void operationComplete(BaseFuture future) throws Exception
            {
                Data data = removeFuture.getData();
                Platform.runLater(() -> onOfferRemoved(data, future.isSuccess(), locationKey));
            }
        });
    }

    private void onOfferRemoved(Data data, boolean success, Number160 locationKey)
    {
        log.debug("onOfferRemoved");
        setDirty(locationKey);

        for (OrderBookListener orderBookListener : orderBookListeners)
            orderBookListener.onOfferRemoved(data, success);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Check dirty flag for a location key
    ///////////////////////////////////////////////////////////////////////////////////////////


    public BooleanProperty getIsDirtyProperty()
    {
        return isDirty;
    }

    public void getDirtyFlag(Currency currency)
    {
        Number160 locationKey = Number160.createHash(currency.getCurrencyCode());
        FutureDHT getFuture = myPeer.get(getDirtyLocationKey(locationKey)).start();
        getFuture.addListener(new BaseFutureListener<BaseFuture>()
        {
            @Override
            public void operationComplete(BaseFuture future) throws Exception
            {
                Data data = getFuture.getData();
                if (data != null)
                {
                    Object object = data.getObject();
                    if (object instanceof Long)
                    {
                        final long lastTimeStamp = (Long) object;
                        //System.out.println("getDirtyFlag " + lastTimeStamp);
                        Platform.runLater(() -> onGetDirtyFlag(lastTimeStamp));
                    }
                }
            }

            @Override
            public void exceptionCaught(Throwable t) throws Exception
            {
                System.out.println("getFuture exceptionCaught " + System.currentTimeMillis());
            }
        });
    }

    private void onGetDirtyFlag(long timeStamp)
    {
        // TODO don't get updates at first execute....
        if (lastTimeStamp != timeStamp)
        {
            isDirty.setValue(!isDirty.get());
        }
        if (lastTimeStamp > 0)
            lastTimeStamp = timeStamp;
        else
            lastTimeStamp++;
    }

    private Number160 getDirtyLocationKey(Number160 locationKey)
    {
        return Number160.createHash(locationKey + "Dirty");
    }

    private void setDirty(Number160 locationKey)
    {
        // we don't want to get an update from dirty for own changes, so update the lastTimeStamp to omit a change trigger
        lastTimeStamp = System.currentTimeMillis();
        try
        {
            FutureDHT putFuture = myPeer.put(getDirtyLocationKey(locationKey)).setData(new Data(lastTimeStamp)).start();
            putFuture.addListener(new BaseFutureListener<BaseFuture>()
            {
                @Override
                public void operationComplete(BaseFuture future) throws Exception
                {
                    //System.out.println("operationComplete");
                }

                @Override
                public void exceptionCaught(Throwable t) throws Exception
                {
                    System.err.println("exceptionCaught");
                }
            });
        } catch (IOException e)
        {
            log.warn("Error at writing dirty flag (timeStamp) " + e.getMessage());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Send message
    ///////////////////////////////////////////////////////////////////////////////////////////

   /* public boolean sendMessage(Object message)
    {
        boolean result = false;
        if (otherPeerAddress != null)
        {
            if (peerConnection != null)
                peerConnection.close();

            peerConnection = myPeer.createPeerConnection(otherPeerAddress, 20);
            if (!peerConnection.isClosed())
            {
                FutureResponse sendFuture = myPeer.sendDirect(peerConnection).setObject(message).start();
                sendFuture.addListener(new BaseFutureAdapter<BaseFuture>()
                {
                    @Override
                    public void operationComplete(BaseFuture baseFuture) throws Exception
                    {
                        if (sendFuture.isSuccess())
                        {
                            final Object object = sendFuture.getObject();
                            Platform.runLater(() -> onResponseFromSend(object));
                        }
                        else
                        {
                            Platform.runLater(() -> onSendFailed());
                        }
                    }
                }
                );
                result = true;
            }
        }
        return result;
    } */
      /*
    private void onResponseFromSend(Object response)
    {
        for (MessageListener messageListener : messageListeners)
            messageListener.onResponseFromSend(response);
    }

    private void onSendFailed()
    {
        for (MessageListener messageListener : messageListeners)
            messageListener.onSendFailed();
    }
      */

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Find peer address
    ///////////////////////////////////////////////////////////////////////////////////////////


    public void getPeerAddress(String pubKeyAsHex, GetPeerAddressListener listener)
    {
        final Number160 location = Number160.createHash(pubKeyAsHex);
        final FutureDHT getPeerAddressFuture = myPeer.get(location).start();
        getPeerAddressFuture.addListener(new BaseFutureAdapter<BaseFuture>()
        {
            @Override
            public void operationComplete(BaseFuture baseFuture) throws Exception
            {
                if (baseFuture.isSuccess() && getPeerAddressFuture.getData() != null)
                {
                    final PeerAddress peerAddress = (PeerAddress) getPeerAddressFuture.getData().getObject();
                    Platform.runLater(() -> listener.onResult(peerAddress));
                }
                else
                {
                    Platform.runLater(() -> listener.onFailed());

                }
            }
        });
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Trade process
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void sendTradingMessage(final PeerAddress peerAddress, TradeMessage tradeMessage, TradeMessageListener listener)
    {
        final PeerConnection peerConnection = myPeer.createPeerConnection(peerAddress, 10);
        final FutureResponse sendFuture = myPeer.sendDirect(peerConnection).setObject(tradeMessage).start();
        sendFuture.addListener(new BaseFutureAdapter<BaseFuture>()
                               {
                                   @Override
                                   public void operationComplete(BaseFuture baseFuture) throws Exception
                                   {
                                       if (sendFuture.isSuccess())
                                       {
                                           Platform.runLater(() -> listener.onResult());
                                       }
                                       else
                                       {
                                           Platform.runLater(() -> listener.onFailed());
                                       }
                                   }
                               }
        );
    }

    public void sendTradeMessage(PeerAddress peerAddress, TradeMessage tradeMessage, TradeMessageListener listener)
    {
        final PeerConnection peerConnection = myPeer.createPeerConnection(peerAddress, 10);
        final FutureResponse sendFuture = myPeer.sendDirect(peerConnection).setObject(tradeMessage).start();
        sendFuture.addListener(new BaseFutureAdapter<BaseFuture>()
                               {
                                   @Override
                                   public void operationComplete(BaseFuture baseFuture) throws Exception
                                   {
                                       if (sendFuture.isSuccess())
                                       {
                                           Platform.runLater(() -> onSendTradingMessageResult(listener));
                                       }
                                       else
                                       {
                                           Platform.runLater(() -> onSendTradingMessageFailed(listener));
                                       }
                                   }
                               }
        );
    }

    private void onSendTradingMessageResult(TradeMessageListener listener)
    {
        listener.onResult();
    }

    private void onSendTradingMessageFailed(TradeMessageListener listener)
    {
        listener.onFailed();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Process incoming tradingMessage
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void processTradingMessage(TradeMessage tradeMessage, PeerAddress sender)
    {
        // log.trace("processTradingMessage TradeId " + tradeMessage.getTradeId());
        log.trace("processTradingMessage instance " + tradeMessage.getClass().getSimpleName());
        log.trace("processTradingMessage instance " + tradeMessage.getClass().getName());
        log.trace("processTradingMessage instance " + tradeMessage.getClass().getCanonicalName());
        log.trace("processTradingMessage instance " + tradeMessage.getClass().getTypeName());

        if (tradeMessage instanceof RequestTakeOfferMessage)
        {
            takeOfferRequestListeners.stream().forEach(e -> e.onTakeOfferRequested(tradeMessage.getTradeId(), sender));
        }
        else if (tradeMessage instanceof AcceptTakeOfferRequestMessage)
        {
            takerPaymentProtocols.get(tradeMessage.getTradeId()).onAcceptTakeOfferRequestMessage();
        }
        else if (tradeMessage instanceof RejectTakeOfferRequestMessage)
        {
            takerPaymentProtocols.get(tradeMessage.getTradeId()).onRejectTakeOfferRequestMessage();
        }
        else if (tradeMessage instanceof TakeOfferFeePayedMessage)
        {
            offererAsBuyerProtocols.get(tradeMessage.getTradeId()).onTakeOfferFeePayedMessage((TakeOfferFeePayedMessage) tradeMessage);
        }
        else if (tradeMessage instanceof RequestTakerDepositPaymentMessage)
        {
            takerPaymentProtocols.get(tradeMessage.getTradeId()).onRequestTakerDepositPaymentMessage((RequestTakerDepositPaymentMessage) tradeMessage);
        }
        else if (tradeMessage instanceof RequestOffererPublishDepositTxMessage)
        {
            offererAsBuyerProtocols.get(tradeMessage.getTradeId()).onRequestOffererPublishDepositTxMessage((RequestOffererPublishDepositTxMessage) tradeMessage);
        }
        else if (tradeMessage instanceof DepositTxPublishedMessage)
        {
            takerPaymentProtocols.get(tradeMessage.getTradeId()).onDepositTxPublishedMessage((DepositTxPublishedMessage) tradeMessage);
        }
        else if (tradeMessage instanceof BankTransferInitedMessage)
        {
            takerPaymentProtocols.get(tradeMessage.getTradeId()).onBankTransferInitedMessage((BankTransferInitedMessage) tradeMessage);
        }
        else if (tradeMessage instanceof PayoutTxPublishedMessage)
        {
            offererAsBuyerProtocols.get(tradeMessage.getTradeId()).onPayoutTxPublishedMessage((PayoutTxPublishedMessage) tradeMessage);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Ping peer
    ///////////////////////////////////////////////////////////////////////////////////////////
    //TODO not working anymore...
    public void pingPeer(String publicKeyAsHex)
    {
        Number160 location = Number160.createHash(publicKeyAsHex);
        final FutureDHT getPeerAddressFuture = myPeer.get(location).start();
        getPeerAddressFuture.addListener(new BaseFutureAdapter<BaseFuture>()
        {
            @Override
            public void operationComplete(BaseFuture baseFuture) throws Exception
            {
                final Data data = getPeerAddressFuture.getData();
                if (data != null && data.getObject() instanceof PeerAddress)
                {
                    final PeerAddress peerAddress = (PeerAddress) data.getObject();
                    Platform.runLater(() -> onAddressFoundPingPeer(peerAddress));
                }
            }
        });
    }


    private void onAddressFoundPingPeer(PeerAddress peerAddress)
    {
        try
        {
            final PeerConnection peerConnection = myPeer.createPeerConnection(peerAddress, 10);
            if (!peerConnection.isClosed())
            {
                FutureResponse sendFuture = myPeer.sendDirect(peerConnection).setObject(PING).start();
                sendFuture.addListener(new BaseFutureAdapter<BaseFuture>()
                                       {
                                           @Override
                                           public void operationComplete(BaseFuture baseFuture) throws Exception
                                           {
                                               if (sendFuture.isSuccess())
                                               {
                                                   final String pong = (String) sendFuture.getObject();
                                                   Platform.runLater(() -> onResponseFromPing(PONG.equals(pong)));
                                               }
                                               else
                                               {
                                                   peerConnection.close();
                                                   Platform.runLater(() -> onResponseFromPing(false));
                                               }
                                           }
                                       }
                );
            }
        } catch (Exception e)
        {
            //  ClosedChannelException can happen, check out if there is a better way to ping a myPeerInstance for online status
        }
    }

    private void onResponseFromPing(boolean success)
    {
        for (PingPeerListener pingPeerListener : pingPeerListeners)
            pingPeerListener.onPingPeerResult(success);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Misc
    ///////////////////////////////////////////////////////////////////////////////////////////

    public PublicKey getPubKey()
    {
        return keyPair.getPublic();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Event Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addMessageListener(OrderBookListener listener)
    {
        orderBookListeners.add(listener);
    }

    public void removeMessageListener(OrderBookListener listener)
    {
        orderBookListeners.remove(listener);
    }

    public void addTakeOfferRequestListener(TakeOfferRequestListener listener)
    {
        takeOfferRequestListeners.add(listener);
    }

    public void removeTakeOfferRequestListener(TakeOfferRequestListener listener)
    {
        takeOfferRequestListeners.remove(listener);
    }

    public void addTakerPaymentProtocol(TakerAsSellerProtocol protocol)
    {
        takerPaymentProtocols.put(protocol.getId(), protocol);
    }

    public void removeTakerPaymentProtocol(TakerAsSellerProtocol protocol)
    {
        takerPaymentProtocols.remove(protocol);
    }

    public void addOffererPaymentProtocol(OffererAsBuyerProtocol protocol)
    {
        offererAsBuyerProtocols.put(protocol.getId(), protocol);
    }

    public void removeOffererPaymentProtocol(OffererAsBuyerProtocol protocol)
    {
        offererAsBuyerProtocols.remove(protocol);
    }

    public void addPingPeerListener(PingPeerListener listener)
    {
        pingPeerListeners.add(listener);
    }

    public void removePingPeerListener(PingPeerListener listener)
    {
        pingPeerListeners.remove(listener);
    }

    public void addArbitratorListener(ArbitratorListener listener)
    {
        arbitratorListeners.add(listener);
    }

    public void removeArbitratorListener(ArbitratorListener listener)
    {
        arbitratorListeners.remove(listener);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private Methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void createMyPeerInstance(int port) throws IOException
    {
        keyPair = DSAKeyUtil.getKeyPair();
        myPeer = new PeerMaker(keyPair).setPorts(port).makeAndListen();
        final FutureBootstrap futureBootstrap = myPeer.bootstrap().setBroadcast().setPorts(MASTER_PEER_PORT).start();
        futureBootstrap.addListener(new BaseFutureAdapter<BaseFuture>()
        {
            @Override
            public void operationComplete(BaseFuture future) throws Exception
            {
                if (futureBootstrap.getBootstrapTo() != null)
                {
                    PeerAddress masterPeerAddress = futureBootstrap.getBootstrapTo().iterator().next();
                    final FutureDiscover futureDiscover = myPeer.discover().setPeerAddress(masterPeerAddress).start();
                    futureDiscover.addListener(new BaseFutureListener<BaseFuture>()
                    {
                        @Override
                        public void operationComplete(BaseFuture future) throws Exception
                        {
                            //System.out.println("operationComplete");
                        }

                        @Override
                        public void exceptionCaught(Throwable t) throws Exception
                        {
                            System.err.println("exceptionCaught");
                        }
                    });
                }
            }
        });
    }

    private void setupStorage()
    {
        myPeer.getPeerBean().setStorage(new StorageDisk(FileUtil.getDirectory(BitSquare.ID + "_tomP2P").getAbsolutePath()));
    }

    private void saveMyAddressToDHT() throws IOException
    {
        Number160 location = Number160.createHash(DSAKeyUtil.getHexStringFromPublicKey(getPubKey()));
        //log.debug("saveMyAddressToDHT location "+location.toString());
        myPeer.put(location).setData(new Data(myPeer.getPeerAddress())).start();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handler
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void setupReplyHandler()
    {
       /* myPeer.setObjectDataReply((sender, request) -> {
            if (!sender.equals(myPeer.getPeerAddress()))
            {
                Platform.runLater(() -> onMessage(request, sender));
            }
            //noinspection ReturnOfNull
            return null;
        });  */

        //noinspection Convert2Lambda
        myPeer.setObjectDataReply(new ObjectDataReply()
        {

            @Override
            public Object reply(PeerAddress sender, Object request) throws Exception
            {
                if (!sender.equals(myPeer.getPeerAddress()))
                {
                    Platform.runLater(() -> onMessage(request, sender));
                }
                //noinspection ReturnOfNull
                return null;
            }
        });
    }

    private void onMessage(Object request, PeerAddress sender)
    {
        if (request instanceof TradeMessage)
        {
            processTradingMessage((TradeMessage) request, sender);
        }
       /* else
        {
            for (OrderBookListener orderBookListener : orderBookListeners)
                orderBookListener.onMessage(request);
        }  */
    }

}
