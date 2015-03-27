/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.trade.protocol.trade.taker;

import io.bitsquare.common.taskrunner.TaskRunner;
import io.bitsquare.p2p.MailboxMessage;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.MessageHandler;
import io.bitsquare.p2p.Peer;
import io.bitsquare.trade.TakerTrade;
import io.bitsquare.trade.protocol.Protocol;
import io.bitsquare.trade.protocol.trade.messages.DepositTxPublishedMessage;
import io.bitsquare.trade.protocol.trade.messages.FiatTransferStartedMessage;
import io.bitsquare.trade.protocol.trade.messages.RequestTakerDepositPaymentMessage;
import io.bitsquare.trade.protocol.trade.messages.TradeMessage;
import io.bitsquare.trade.protocol.trade.taker.models.TakerTradeProcessModel;
import io.bitsquare.trade.protocol.trade.taker.tasks.BroadcastTakeOfferFeeTx;
import io.bitsquare.trade.protocol.trade.taker.tasks.CreateAndSignContract;
import io.bitsquare.trade.protocol.trade.taker.tasks.CreateTakeOfferFeeTx;
import io.bitsquare.trade.protocol.trade.taker.tasks.ProcessDepositTxPublishedMessage;
import io.bitsquare.trade.protocol.trade.taker.tasks.ProcessFiatTransferStartedMessage;
import io.bitsquare.trade.protocol.trade.taker.tasks.ProcessRequestTakerDepositPaymentMessage;
import io.bitsquare.trade.protocol.trade.taker.tasks.SendPayoutTxToOfferer;
import io.bitsquare.trade.protocol.trade.taker.tasks.SendRequestDepositTxInputsMessage;
import io.bitsquare.trade.protocol.trade.taker.tasks.SendSignedTakerDepositTx;
import io.bitsquare.trade.protocol.trade.taker.tasks.SignAndPublishPayoutTx;
import io.bitsquare.trade.protocol.trade.taker.tasks.TakerCommitDepositTx;
import io.bitsquare.trade.protocol.trade.taker.tasks.TakerCreatesAndSignsDepositTx;
import io.bitsquare.trade.protocol.trade.taker.tasks.VerifyOfferFeePayment;
import io.bitsquare.trade.protocol.trade.taker.tasks.VerifyOffererAccount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.bitsquare.util.Validator.nonEmptyStringOf;

public class TakerProtocol implements Protocol {
    private static final Logger log = LoggerFactory.getLogger(TakerProtocol.class);

    private final TakerTrade takerTrade;
    private final TakerTradeProcessModel takerTradeProcessModel;
    private final MessageHandler messageHandler;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TakerProtocol(TakerTrade takerTrade) {
        log.debug("New SellerAsTakerProtocol " + this);
        this.takerTrade = takerTrade;
        takerTradeProcessModel = takerTrade.getProcessModel();
        
        messageHandler = this::handleMessage;
        takerTradeProcessModel.messageService.addMessageHandler(messageHandler);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void cleanup() {
        log.debug("cleanup " + this);
        takerTradeProcessModel.messageService.removeMessageHandler(messageHandler);
    }

    public void setMailboxMessage(MailboxMessage mailboxMessage) {
        log.debug("setMailboxMessage " + mailboxMessage);
        // Might be called twice, so check that its only processed once
        if (takerTradeProcessModel.mailboxMessage == null) {
            takerTradeProcessModel.mailboxMessage = mailboxMessage;
            if (mailboxMessage instanceof FiatTransferStartedMessage) {
                handleFiatTransferStartedMessage((FiatTransferStartedMessage) mailboxMessage);
            }
            else if (mailboxMessage instanceof DepositTxPublishedMessage) {
                handleDepositTxPublishedMessage((DepositTxPublishedMessage) mailboxMessage);
            }
        }
    }

    public void takeAvailableOffer() {
        TaskRunner<TakerTrade> taskRunner = new TaskRunner<>(takerTrade,
                () -> {
                    log.debug("taskRunner at takeAvailableOffer completed");
                },
                (errorMessage) -> handleTaskRunnerFault(errorMessage));

        taskRunner.addTasks(
                CreateTakeOfferFeeTx.class,
                BroadcastTakeOfferFeeTx.class,
                SendRequestDepositTxInputsMessage.class
        );
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleRequestTakerDepositPaymentMessage(RequestTakerDepositPaymentMessage tradeMessage) {
        takerTradeProcessModel.setTradeMessage(tradeMessage);

        TaskRunner<TakerTrade> taskRunner = new TaskRunner<>(takerTrade,
                () -> {
                    log.debug("taskRunner at handleTakerDepositPaymentRequestMessage completed");
                },
                (errorMessage) -> handleTaskRunnerFault(errorMessage));

        taskRunner.addTasks(
                ProcessRequestTakerDepositPaymentMessage.class,
                VerifyOffererAccount.class,
                CreateAndSignContract.class,
                TakerCreatesAndSignsDepositTx.class,
                SendSignedTakerDepositTx.class
        );
        taskRunner.run();
    }

    private void handleDepositTxPublishedMessage(DepositTxPublishedMessage tradeMessage) {
        takerTradeProcessModel.setTradeMessage(tradeMessage);

        TaskRunner<TakerTrade> taskRunner = new TaskRunner<>(takerTrade,
                () -> {
                    log.debug("taskRunner at handleDepositTxPublishedMessage completed");
                },
                (errorMessage) -> handleTaskRunnerFault(errorMessage));

        taskRunner.addTasks(
                ProcessDepositTxPublishedMessage.class,
                TakerCommitDepositTx.class
        );
        taskRunner.run();
    }

    private void handleFiatTransferStartedMessage(FiatTransferStartedMessage tradeMessage) {
        takerTradeProcessModel.setTradeMessage(tradeMessage);

        TaskRunner<TakerTrade> taskRunner = new TaskRunner<>(takerTrade,
                () -> {
                    log.debug("taskRunner at handleFiatTransferStartedMessage completed");
                },
                (errorMessage) -> handleTaskRunnerFault(errorMessage));

        taskRunner.addTasks(ProcessFiatTransferStartedMessage.class);
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    // User clicked the "bank transfer received" button, so we release the funds for pay out
    public void onFiatPaymentReceived() {
        takerTrade.setProcessState(TakerTrade.TakerProcessState.FIAT_PAYMENT_RECEIVED);

        TaskRunner<TakerTrade> taskRunner = new TaskRunner<>(takerTrade,
                () -> {
                    log.debug("taskRunner at handleFiatReceivedUIEvent completed");

                    // we are done!
                    takerTradeProcessModel.onComplete();
                },
                (errorMessage) -> handleTaskRunnerFault(errorMessage));

        taskRunner.addTasks(
                SignAndPublishPayoutTx.class,
                VerifyOfferFeePayment.class,
                SendPayoutTxToOfferer.class
        );
        taskRunner.run();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Massage dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleMessage(Message message, Peer sender) {
        log.trace("handleNewMessage: message = " + message.getClass().getSimpleName() + " from " + sender);
        if (message instanceof TradeMessage) {
            TradeMessage tradeMessage = (TradeMessage) message;
            nonEmptyStringOf(tradeMessage.tradeId);

            if (tradeMessage.tradeId.equals(takerTradeProcessModel.id)) {
                if (tradeMessage instanceof RequestTakerDepositPaymentMessage) {
                    handleRequestTakerDepositPaymentMessage((RequestTakerDepositPaymentMessage) tradeMessage);
                }
                else if (tradeMessage instanceof DepositTxPublishedMessage) {
                    handleDepositTxPublishedMessage((DepositTxPublishedMessage) tradeMessage);
                }
                else if (tradeMessage instanceof FiatTransferStartedMessage) {
                    handleFiatTransferStartedMessage((FiatTransferStartedMessage) tradeMessage);
                }
                else {
                    log.error("Incoming message not supported. " + tradeMessage);
                }
            }
        }
    }

    private void handleTaskRunnerFault(String errorMessage) {
        cleanup();
    }

}
