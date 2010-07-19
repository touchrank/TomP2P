/*
 * Copyright 2009 Thomas Bocek
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package net.tomp2p.rpc;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;

import net.tomp2p.connection.ConnectionBean;
import net.tomp2p.connection.PeerBean;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.message.Message;
import net.tomp2p.message.MessageCodec;
import net.tomp2p.message.Message.Command;
import net.tomp2p.message.Message.Type;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.storage.Data;
import net.tomp2p.storage.TrackerData;
import net.tomp2p.storage.TrackerStorage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TrackerRPC extends ReplyHandler
{
	final private static Logger logger = LoggerFactory.getLogger(TrackerRPC.class);

	// final private TrackerStorage trackerStorage;
	// final private int trackerSize;
	// final private int ttlMillis;
	/**
	 * 
	 * @param peerBean
	 * @param atLeastTrackerSize Upper size is 27, lower size can be specified.
	 * @param dataSize
	 */
	public TrackerRPC(PeerBean peerBean, ConnectionBean connectionBean)
	{
		super(peerBean, connectionBean);
		registerIoHandler(Command.TRACKER_ADD, Command.TRACKER_GET);
	}
	
	public PeerAddress getPeerAddress()
	{
		return peerBean.getServerPeerAddress();
	}

	public FutureResponse addToTrackerReplication(PeerAddress remoteNode, Number160 locationKey,
			Number160 domainKey, Data data, boolean signMessage)
	{
		nullCheck(remoteNode, locationKey, domainKey);
		final Message message = createMessage(remoteNode, Command.TRACKER_ADD, Type.REQUEST_2);
		message.setKeyKey(locationKey, domainKey);
		if (signMessage)
			message.setPublicKeyAndSign(peerBean.getKeyPair());
		final RequestHandler requestHandler = new RequestHandler(peerBean, connectionBean, message);
		Map<Number160, Data> c = new HashMap<Number160, Data>(1);
		c.put(data.getHash(), data);
		message.setDataMap(c);
		return requestHandler.sendTCP();
	}

	public FutureResponse addToTracker(final PeerAddress remoteNode, final Number160 locationKey,
			final Number160 domainKey, final Data attachement, boolean signMessage)
	{
		nullCheck(remoteNode, locationKey, domainKey);
		final Message message = createMessage(remoteNode, Command.TRACKER_ADD, Type.REQUEST_1);
		message.setKeyKey(locationKey, domainKey);
		if (signMessage)
			message.setPublicKeyAndSign(peerBean.getKeyPair());
		final RequestHandler requestHandler = new RequestHandler(peerBean, connectionBean, message);
		if (attachement != null)
		{
			Map<Number160, Data> c = new HashMap<Number160, Data>(1);
			c.put(attachement.getHash(), attachement);
			message.setDataMap(c);
			return requestHandler.sendTCP();
		}
		else
			return requestHandler.sendUDP();
	}

	public FutureResponse getFromTracker(final PeerAddress remoteNode, final Number160 locationKey,
			final Number160 domainKey, boolean expectAttachement, boolean signMessage)
	{
		nullCheck(remoteNode, locationKey, domainKey);
		final Message message = createMessage(remoteNode, Command.TRACKER_GET, Type.REQUEST_1);
		message.setKeyKey(locationKey, domainKey);
		if (signMessage)
			message.setPublicKeyAndSign(peerBean.getKeyPair());
		final RequestHandler requestHandler = new RequestHandler(peerBean, connectionBean, message);
		if (expectAttachement)
			return requestHandler.sendTCP();
		else
			return requestHandler.sendUDP();
	}

	@Override
	public boolean checkMessage(Message message)
	{
		return (message.getType() == Type.REQUEST_1 || message.getType() == Type.REQUEST_2)
				&& message.getKey1() != null && message.getKey2() != null;
	}

	@Override
	public Message handleResponse(Message message) throws Exception
	{
		boolean direct = message.getType() == Type.REQUEST_1;
		final Message responseMessage = createMessage(message.getSender(), message.getCommand(),
				Type.OK);
		responseMessage.setMessageId(message.getMessageId());
		final TrackerStorage trackerStorage = peerBean.getTrackerStorage();
		Number160 locationKey = message.getKey1();
		Number160 domainKey = message.getKey2();
		if (direct)
		{
			Map<PeerAddress, Data> peerDataMap = trackerStorage.get(locationKey, domainKey);
			if (peerDataMap == null)
				responseMessage.setPeerDataMap(new HashMap<PeerAddress, Data>());
			else
				responseMessage.setPeerDataMap(peerDataMap);
			PeerAddress senderAddress = message.getSender();
			if (message.getCommand() == Command.TRACKER_ADD)
			{
				if (logger.isDebugEnabled())
					logger.debug("tracker put on(" + peerBean.getServerPeerAddress()
							+ ") locationKey:" + locationKey + ", domainKey:" + domainKey
							+ ", address:" + senderAddress);
				// TODO: make a good selection here!
				if (trackerStorage.size(locationKey, domainKey) < trackerStorage.getTrackerSize())
				{
					Map<Number160, Data> dataMap = message.getDataMap();
					// Collection<Data> c = .values();
					final Data attachement = (dataMap != null && dataMap.size() >= 1) ? dataMap
							.values().iterator().next() : new Data(MessageCodec.EMPTY_BYTE_ARRAY);
					// public key is not set in the data, but in the message
					PublicKey publicKey = message.getPublicKey();
					if (!trackerStorage.put(locationKey, domainKey, senderAddress, publicKey,
							attachement))
						responseMessage.setType(Message.Type.DENIED);
				}
				else
					responseMessage.setType(Message.Type.DENIED);
			}
			else
			{
				if (logger.isDebugEnabled())
					logger.debug("tracker get on(" + peerBean.getServerPeerAddress()
							+ ") locationKey:" + locationKey + ", domainKey:" + domainKey
							+ ", address:" + senderAddress);
				if (peerDataMap == null)
					responseMessage.setType(Message.Type.NOT_FOUND);
			}
		}
		// this is for replication. If we got something here, the other peer thinks that I'm responible
		else
		{
			Map<Number160, Data> dataMap = message.getDataMap();
			Data data = dataMap.values().iterator().next();
			TrackerData trackerData=(TrackerData)data.getObject();
			if (!trackerStorage.put(locationKey, domainKey, trackerData.getPeerAddress(), null,
					trackerData.getAttachement()))
				responseMessage.setType(Message.Type.DENIED);
		}
		return responseMessage;
	}
}