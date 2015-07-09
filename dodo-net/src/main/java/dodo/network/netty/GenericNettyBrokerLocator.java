/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package dodo.network.netty;

import dodo.network.BrokerLocator;
import dodo.network.BrokerNotAvailableException;
import dodo.network.BrokerRejectedConnectionException;
import dodo.network.Channel;
import dodo.network.ChannelEventListener;
import dodo.network.ConnectionRequestInfo;
import dodo.network.Message;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeoutException;

/**
 * Network connection, based on Netty
 *
 * @author enrico.olivelli
 */
public abstract class GenericNettyBrokerLocator implements BrokerLocator {

    protected abstract InetSocketAddress getServer();

    @Override
    public Channel connect(ChannelEventListener messageReceiver, ConnectionRequestInfo workerInfo) throws InterruptedException, BrokerNotAvailableException, BrokerRejectedConnectionException {
        boolean ok = false;
        NettyConnector connector = new NettyConnector(messageReceiver);
        try {
            InetSocketAddress addre = getServer();
            if (addre == null) {
                throw new BrokerNotAvailableException(new Exception("no broker available"));
            }
            connector.setPort(addre.getPort());
            connector.setHost(addre.getAddress().getHostAddress());
            NettyChannel channel;
            try {
                channel = connector.connect();
            } catch (final Exception e) {
                throw new BrokerNotAvailableException(e);
            }

            Message acceptMessage = Message.WORKER_CONNECTION_REQUEST(workerInfo.getWorkerId(), workerInfo.getProcessId(), workerInfo.getLocation(), workerInfo.getRunningTaskIds());
            try {
                Message connectionResponse = channel.sendMessageWithReply(acceptMessage, 10000);
                if (connectionResponse.type == Message.TYPE_ACK) {
                    ok = true;
                    return channel;
                } else {
                    throw new BrokerRejectedConnectionException("Broker rejected connection, response message:" + connectionResponse);
                }
            } catch (TimeoutException err) {
                throw new BrokerNotAvailableException(err);
            }
        } finally {
            if (!ok && connector != null) {
                connector.close();
            }
        }
    }
}
