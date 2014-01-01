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

package net.tomp2p.connection;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureDone;
import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerSocketAddress;
import net.tomp2p.peers.PeerStatusListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a peer and listens to incoming connections. The result of creating
 * this class is the connection bean and the peer bean. While the connection
 * bean holds information that can be shared, the peer bean holds information
 * that is unique for each peer.
 * 
 * @author Thomas Bocek
 * 
 */
public class PeerCreator {
	private static final Logger LOG = LoggerFactory.getLogger(PeerCreator.class);

	private final ConnectionBean connectionBean;
	private final PeerBean peerBean;

	private final List<PeerCreator> childConnections = new ArrayList<PeerCreator>();

	private final EventLoopGroup workerGroup;

	private final EventLoopGroup bossGroup;

	private final boolean master;

	private final FutureDone<Void> futureServerDone = new FutureDone<Void>();

	/**
	 * Creates a master peer and starts UPD and TCP channels.
	 * 
	 * @param p2pId
	 *            The id of the network
	 * @param peerId
	 *            The id of this peer
	 * @param keyPair
	 *            The key pair or null
	 * @param channelServerConficuration
	 *            The server configuration to create the channel server that is
	 *            used for listening for incoming connections
	 * @param channelClientConfiguration
	 *            The client side configuration
	 * @param peerStatusListeners
	 *            The status listener for offline peers
	 * @throws IOException
	 *             If the startup of listening to connections failed
	 */
	public PeerCreator(final int p2pId, final Number160 peerId, final KeyPair keyPair,
	        final ChannelServerConficuration channelServerConficuration,
	        final ChannelClientConfiguration channelClientConfiguration,
	        final PeerStatusListener[] peerStatusListeners, ScheduledExecutorService timer) throws IOException {
		peerBean = new PeerBean(keyPair);
		peerBean.peerStatusListeners(peerStatusListeners);
		workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory(ConnectionBean.THREAD_NAME
		        + "worker-client/server - "));
		bossGroup = new NioEventLoopGroup(2, new DefaultThreadFactory(ConnectionBean.THREAD_NAME + "boss - "));

		Dispatcher dispatcher = new Dispatcher(p2pId, peerBean, channelServerConficuration.heartBeatMillis());
		Reservation reservation = new Reservation(workerGroup, channelClientConfiguration);

		final ChannelServer channelServer = new ChannelServer(bossGroup, workerGroup, channelServerConficuration,
		        dispatcher, peerStatusListeners);
		channelServer.startup();

		PeerAddress self = findPeerAddress(peerId, channelClientConfiguration, channelServerConficuration);
		peerBean.serverPeerAddress(self);
		if (LOG.isInfoEnabled()) {
			LOG.info("Visible address to other peers: " + self);
		}

		Sender sender = new Sender(peerStatusListeners, channelClientConfiguration, dispatcher);

		NATUtils natUtils = new NATUtils();
		connectionBean = new ConnectionBean(p2pId, dispatcher, sender, channelServer, reservation,
		        channelClientConfiguration, natUtils, timer);
		this.master = true;
	}

	/**
	 * Creates a slave peer that will attach itself to a master peer.
	 * 
	 * @param parent
	 *            The parent peer
	 * @param peerId
	 *            The id of this peer
	 * @param keyPair
	 *            The key pair or null
	 */
	public PeerCreator(final PeerCreator parent, final Number160 peerId, final KeyPair keyPair) {
		parent.childConnections.add(this);
		this.workerGroup = parent.workerGroup;
		this.bossGroup = parent.bossGroup;
		this.connectionBean = parent.connectionBean;
		this.peerBean = new PeerBean(keyPair);
		PeerAddress self = parent.peerBean().serverPeerAddress().changePeerId(peerId);
		this.peerBean.serverPeerAddress(self);
		this.master = false;
	}

	/**
	 * Shutdown the peer. If the peer is a master, then also the connections and
	 * the server will be closed, otherwise its just de-registering.
	 * 
	 * @return The future when the shutdown is complete
	 */
	public FutureDone<Void> shutdown() {
		if (master) {
			LOG.debug("shutdown in progress...");
		}
		// de-register in dispatcher
		connectionBean.dispatcher().removeIoHandler(peerBean().serverPeerAddress().getPeerId());
		// shutdown running tasks for this peer
		if (peerBean.maintenanceTask() != null) {
			peerBean.maintenanceTask().shutdown();
		}
		if (peerBean.replicationExecutor() != null) {
			peerBean.replicationExecutor().shutdown();
		}
		// shutdown all children
		if (!master) {
			for (PeerCreator peerCreator : childConnections) {
				peerCreator.shutdown();
			}
			return shutdownFuture().setDone();
		}
		// shutdown the timer
		connectionBean.timer().shutdown();
		
		LOG.debug("starting shutdown done in client...");
		connectionBean.reservation().shutdown().addListener(new BaseFutureAdapter<FutureDone<Void>>() {
			@Override
			public void operationComplete(final FutureDone<Void> future) throws Exception {
				connectionBean.channelServer().shutdown().addListener(new BaseFutureAdapter<FutureDone<Void>>() {
					@SuppressWarnings({ "unchecked", "rawtypes" })
                    @Override
					public void operationComplete(final FutureDone<Void> future) throws Exception {
						workerGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).addListener(new GenericFutureListener() {
							@Override
							public void operationComplete(final Future future) throws Exception {
								LOG.debug("shutdown done in client / workerGroup...");
								bossGroup.shutdownGracefully(0, 0, TimeUnit.SECONDS).addListener(
								        new GenericFutureListener() {
									        @Override
									        public void operationComplete(final Future future) throws Exception {
										        LOG.debug("shutdown done in client / bossGroup...");
										        shutdownFuture().setDone();
									        }
								        });
							}
						});
					}
				});

			}
		});
		// this is blocking
		connectionBean.natUtils().shutdown();
		return shutdownFuture();
	}

	/**
	 * @return The shutdown future that is used when calling {@link #shutdown()}
	 */
	public FutureDone<Void> shutdownFuture() {
		return futureServerDone;
	}

	/**
	 * @return The bean that holds information that is unique for all peers
	 */
	public PeerBean peerBean() {
		return peerBean;
	}

	/**
	 * @return The bean that holds information that may be shared amoung peers
	 */
	public ConnectionBean connectionBean() {
		return connectionBean;
	}

	/**
	 * Creates the {@link PeerAddress} based on the network discovery that was
	 * done in
	 * {@link #ChannelServer(Bindings, int, int, ChannelServerConficuration)}.
	 * 
	 * @param peerId
	 *            The id of this peer
	 * @return The peer address of this peer
	 * @throws IOException
	 *             If the address could not be determined
	 */
	private PeerAddress findPeerAddress(final Number160 peerId,
	        final ChannelClientConfiguration channelClientConfiguration,
	        final ChannelServerConficuration channelServerConficuration) throws IOException {
		final String status = DiscoverNetworks.discoverInterfaces(channelClientConfiguration.externalBindings());
		if (LOG.isInfoEnabled()) {
			LOG.info("Status of external search: " + status);
		}
		if (channelClientConfiguration.externalBindings().foundAddresses().size() == 0) {
			throw new IOException("Not listening to anything. Maybe your binding information is wrong.");
		}
		InetAddress outsideAddress = channelClientConfiguration.externalBindings().foundAddresses().get(0);
		final PeerSocketAddress peerSocketAddress = new PeerSocketAddress(outsideAddress, channelServerConficuration
		        .ports().externalTCPPort(), channelServerConficuration.ports().externalUDPPort());
		final PeerAddress self = new PeerAddress(peerId, peerSocketAddress,
		        channelServerConficuration.isBehindFirewall(), channelServerConficuration.isBehindFirewall(), false,
		        new PeerSocketAddress[] {});
		return self;
	}
}
