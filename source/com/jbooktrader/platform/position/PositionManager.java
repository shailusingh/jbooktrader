package com.jbooktrader.platform.position;

import com.ib.client.*;
import com.jbooktrader.platform.model.*;
import com.jbooktrader.platform.performance.*;
import com.jbooktrader.platform.report.*;
import com.jbooktrader.platform.strategy.*;
import com.jbooktrader.platform.trader.*;
import com.jbooktrader.platform.util.*;

import java.text.*;
import java.util.*;

/**
 * Position manager keeps track of current positions and executions.
 */
public class PositionManager {
    private static final String LINE_SEP = System.getProperty("line.separator");
    private final NumberFormat nf2;
    private final LinkedList<Position> positionsHistory;
    private final Strategy strategy;
    private final Report eventReport;
    private final TraderAssistant traderAssistant;
    private final PerformanceManager performanceManager;

    private int position;
    private double avgFillPrice;
    private volatile boolean orderExecutionPending;

    public PositionManager(Strategy strategy) {
        this.strategy = strategy;
        positionsHistory = new LinkedList<Position>();
        eventReport = Dispatcher.getReporter();
        traderAssistant = Dispatcher.getTrader().getAssistant();
        performanceManager = strategy.getPerformanceManager();
        nf2 = NumberFormatterFactory.getNumberFormatter(2);
    }

    public LinkedList<Position> getPositionsHistory() {
        return positionsHistory;
    }

    public int getPosition() {
        return position;
    }


    public void setAvgFillPrice(double avgFillPrice) {
        this.avgFillPrice = avgFillPrice;
    }

    public double getAvgFillPrice() {
        return avgFillPrice;
    }

    public synchronized void update(OpenOrder openOrder) {
        Order order = openOrder.getOrder();
        String action = order.m_action;
        int sharesFilled = openOrder.getSharesFilled();
        int quantity = 0;

        if (action.equals("SELL")) {
            quantity = -sharesFilled;
        }

        if (action.equals("BUY")) {
            quantity = sharesFilled;
        }

        // current position after the execution
        position += quantity;
        avgFillPrice = openOrder.getAvgFillPrice();


        performanceManager.update(quantity, avgFillPrice, position);

        Dispatcher.Mode mode = Dispatcher.getMode();
        if ((mode != Dispatcher.Mode.Optimization)) {
            positionsHistory.add(new Position(openOrder.getDate(), position, avgFillPrice));
            StringBuilder msg = new StringBuilder();
            msg.append(strategy.getName()).append(": ");
            msg.append("Order ").append(openOrder.getId()).append(" is filled.  ");
            msg.append("Avg Fill Price: ").append(avgFillPrice).append(". ");
            msg.append("Position: ").append(getPosition());
            eventReport.report(msg.toString());
            strategy.report();
        }

        orderExecutionPending = false;

        // remote notification, if enabled
        if (mode == Dispatcher.Mode.Trade || mode == Dispatcher.Mode.ForwardTest) {
            String msg = "Event type: Trade" + LINE_SEP;
            msg += "Strategy: " + strategy.getName() + LINE_SEP;
            msg += "New Position: " + position + LINE_SEP;
            msg += "Avg Fill Price: " + avgFillPrice + LINE_SEP;
            msg += "Trade P&L: " + nf2.format(performanceManager.getTradeProfit()) + LINE_SEP;
            msg += "Total P&L: " + nf2.format(performanceManager.getNetProfit()) + LINE_SEP;
            SecureMailSender.getInstance().send(msg);
        }
    }

    public void trade() {
        if (traderAssistant.isConnected() && !orderExecutionPending) {
            int newPosition = strategy.getPosition();
            int quantity = newPosition - position;
            if (quantity != 0) {
                orderExecutionPending = true;
                String action = (quantity > 0) ? "BUY" : "SELL";
                Contract contract = strategy.getContract();
                traderAssistant.placeMarketOrder(contract, Math.abs(quantity), action, strategy);
            }
        }
    }
}
