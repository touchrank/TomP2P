package net.tomp2p.relay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import net.tomp2p.connection.PeerConnection;
import net.tomp2p.futures.BaseFutureAdapter;
import net.tomp2p.futures.FutureDone;
import net.tomp2p.futures.FutureForkJoin;
import net.tomp2p.futures.FuturePeerConnection;
import net.tomp2p.futures.FutureResponse;
import net.tomp2p.futures.Futures;
import net.tomp2p.message.Message;
import net.tomp2p.message.Message.Type;
import net.tomp2p.p2p.Peer;
import net.tomp2p.peers.PeerAddress;
import net.tomp2p.peers.PeerSocketAddress;
import net.tomp2p.relay.buffer.BufferRequestListener;
import net.tomp2p.relay.buffer.BufferedRelayClient;
import net.tomp2p.rpc.RPC;
import net.tomp2p.utils.ConcurrentCacheSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The relay manager is responsible for setting up and maintaining connections
 * to relay peers and contains all information about the relays.
 * 
 * @author Raphael Voellmy
 * @author Thomas Bocek
 * @author Nico Rutishauser
 * 
 */
public class DistributedRelay /*implements BufferRequestListener*/ {

	private final static Logger LOG = LoggerFactory.getLogger(DistributedRelay.class);

	private final Peer peer;
	private final RelayRPC relayRPC;

	//private final List<BaseRelayClient> relayClients;
	private final Set<PeerAddress> failedRelays;
	private final Map<PeerAddress, PeerConnection> activeClients;

	//private final Collection<RelayListener> relayListeners;
	private final RelayClientConfig relayConfig;
	
	private FutureDone<Void> shutdownFuture = new FutureDone<Void>();
	
	private volatile boolean shutdown = false;

	/**
	 * @param peer
	 *            the unreachable peer
	 * @param relayRPC
	 *            the relay RPC
	 * @param maxFail 
	 * @param relayConfig 
	 * @param maxRelays
	 *            maximum number of relay peers to set up
	 * @param relayType
	 *            the kind of the relay connection
	 */
	public DistributedRelay(final Peer peer, RelayRPC relayRPC, RelayClientConfig relayConfig) {
		this.peer = peer;
		this.relayRPC = relayRPC;
		this.relayConfig = relayConfig;

		activeClients = Collections.synchronizedMap(new HashMap<PeerAddress, PeerConnection>());
		failedRelays = new ConcurrentCacheSet<PeerAddress>(relayConfig.failedRelayWaitTime());
		//relayListeners = Collections.synchronizedList(new ArrayList<RelayListener>(1));
	}

	public RelayClientConfig relayConfig() {
		return relayConfig;
	}

	/**
	 * Returns connections to current relay peers
	 * 
	 * @return List of PeerAddresses of the relay peers (copy)
	 */
	public Map<PeerAddress, PeerConnection> activeClients() {
		synchronized (activeClients) {
			// make a copy
			return Collections.unmodifiableMap(new HashMap<PeerAddress, PeerConnection>(activeClients));
		}
	}

	/*public void addRelayListener(RelayListener relayListener) {
		synchronized (relayListeners) {
			relayListeners.add(relayListener);
		}
	}*/

	public FutureDone<Void> shutdown() {
		shutdown = true;
		synchronized (activeClients) {
			for (Map.Entry<PeerAddress, PeerConnection> entry: activeClients.entrySet()) {
				entry.getValue().close();
			}
		}

		/*synchronized (relayListeners) {
			relayListeners.clear();
		}*/

		return shutdownFuture;
	}

	/**
	 * Sets up relay connections to other peers. The number of relays to set up
	 * is determined by {@link PeerAddress#MAX_RELAYS} or passed to the
	 * constructor of this class. It is important that we call this after we
	 * bootstrapped and have recent information in our peer map.
	 * 
	 * @return RelayFuture containing a {@link DistributedRelay} instance
	 */
	public DistributedRelay setupRelays(final RelayCallback relayCallback) {
		startConnectionsOpen(relayCallback);
		return this;
	}
	
	private List<PeerAddress> relayCandidates() {
		final List<PeerAddress> relayCandidates;
		if (relayConfig.manualRelays().isEmpty()) {
			// Get the neighbors of this peer that could possibly act as relays. Relay
			// candidates are neighboring peers that are not relayed themselves and have
			// not recently failed as relay or denied acting as relay.
			relayCandidates = peer.distributedRouting().peerMap().all();
			// remove those who we know have failed
			relayCandidates.removeAll(failedRelays);
		} else {
			// if the user sets manual relays, the failed relays are not removed, as this has to be done by
			// the user
			relayCandidates = new ArrayList<PeerAddress>(relayConfig.manualRelays());
		}

		//filterRelayCandidates
		for (Iterator<PeerAddress> iterator = relayCandidates.iterator(); iterator.hasNext();) {
			PeerAddress candidate = iterator.next();

			// filter peers that are relayed themselves
			if (candidate.isRelayed()) {
				iterator.remove();
				continue;
			}

			//  Remove recently failed relays, peers that are relayed themselves and
			// peers that are already relays
			if (activeClients.containsKey(candidate)) {
				iterator.remove();
			}
		}
		LOG.trace("Found {} addtional relay candidates: {}", relayCandidates.size(), relayCandidates);
		
		return relayCandidates;
	}
	
	final AtomicInteger activity = new AtomicInteger(0);
	
	/**
	 * The relay setup is called sequentially until the number of max relays is reached. If a peerconnection goes down, it will search for other relays
	 * @param relayCallback 
	 */
	private void startConnectionsOpen(final RelayCallback relayCallback) {
		
		synchronized (activeClients) {
			if(activity.incrementAndGet() == 1 && shutdown && activeClients.isEmpty()) {
				shutdownFuture.done();
				LOG.debug("shutting down, don't restart relays");
				return;
			}
		}
		
		if(activeClients.size() >= relayConfig.type().maxRelayCount()) {
			LOG.debug("we have enough relays");
			return;
		}
		
		//get candidates
		final List<PeerAddress> relayCandidates = relayCandidates();
		if(relayCandidates.isEmpty()) {
			LOG.debug("no more relays");
			return;
		}
		
		final PeerAddress candidate = relayCandidates.get(0);
		final FutureDone<PeerConnection> futureDone = relayRPC.sendSetupMessage(candidate, relayConfig);
		futureDone.addListener(new BaseFutureAdapter<FutureDone<PeerConnection>>() {
			@Override
			public void operationComplete(final FutureDone<PeerConnection> future)
					throws Exception {
				
				if(future.isSuccess()) {
					synchronized (activeClients) {
						activeClients.put(candidate, future.object());
						updatePeerAddress();
					}
					LOG.debug("found relay: {}", candidate);
					relayCallback.onRelayAdded(candidate, future.object());
					startConnectionsOpen(relayCallback);
					
					future.object().closeFuture().addListener(new BaseFutureAdapter<FutureDone<Void>>() {
						@Override
						public void operationComplete(final FutureDone<Void> futureClose)
								throws Exception {
							failedRelays.add(future.object().remotePeer());
							synchronized (activeClients) {
								activeClients.remove(candidate, future.object());
								updatePeerAddress();
							}
							LOG.debug("lost/offline relay: {}", candidate);
							relayCallback.onRelayRemoved(candidate, future.object());
							startConnectionsOpen(relayCallback);
						}
					});
				} else {
					synchronized (activeClients) {
						activeClients.remove(candidate, future.object());
						updatePeerAddress();
					}
					LOG.debug("bad relay: {}", candidate);
					relayCallback.onRelayRemoved(candidate, future.object());
					startConnectionsOpen(relayCallback);
				}
				synchronized (activeClients) {
					if(activeClients.isEmpty() && shutdown && activity.decrementAndGet() == 0) {
						shutdownFuture.done();
					}
				}
				
			}
		});		
	}

	/**
	 * Updates the peer's PeerAddress: Adds the relay addresses to the peer
	 * address, updates the firewalled flags, and bootstraps to announce its new
	 * relay peers.
	 */
	private void updatePeerAddress() {
		// add relay addresses to peer address
		boolean hasRelays = !activeClients.isEmpty();

		Collection<PeerSocketAddress> socketAddresses = new ArrayList<PeerSocketAddress>(activeClients.size());
		
		//we can have more than the max relay count in our active client list.
		int max = relayConfig.type().maxRelayCount();
		int i = 0;
		for (PeerAddress relay : activeClients.keySet()) {
			socketAddresses.add(new PeerSocketAddress(relay.inetAddress(), relay.tcpPort(), relay.udpPort()));
			if(i++ >= max) {
				break;
			}
		}
		

		// update firewalled and isRelayed flags
		PeerAddress newAddress = peer.peerAddress().changeFirewalledTCP(!hasRelays).changeFirewalledUDP(!hasRelays)
				.changeRelayed(hasRelays).changePeerSocketAddresses(socketAddresses).changeSlow(hasRelays && relayConfig.type().isSlow());
		peer.peerBean().serverPeerAddress(newAddress);
		LOG.debug("Updated peer address {}, isrelay = {}", newAddress, hasRelays);
	}


	/*@Override
	public FutureDone<Void> sendBufferRequest(String relayPeerId) {
		for (BaseRelayClient relayConnection : relayClients()) {
			String peerId = relayConnection.relayAddress().peerId().toString();
			if (peerId.equals(relayPeerId) && relayConnection instanceof BufferedRelayClient) {
				return ((BufferedRelayClient) relayConnection).sendBufferRequest();
			}
		}

		LOG.warn("No connection to relay {} found. Ignoring the message.", relayPeerId);
		return new FutureDone<Void>().failed("No connection to relay " + relayPeerId + " found");
	}*/
}
