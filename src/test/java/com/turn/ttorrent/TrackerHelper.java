package com.turn.ttorrent;

import com.sun.org.apache.xerces.internal.impl.dv.util.HexBin;
import com.turn.ttorrent.client.CommunicationListener;
import com.turn.ttorrent.client.announce.AnnounceResponseListener;
import com.turn.ttorrent.client.announce.HTTPTrackerClient;
import com.turn.ttorrent.client.peer.MessageListener;
import com.turn.ttorrent.common.*;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.common.protocol.TrackerMessage;
import com.turn.ttorrent.stubs.SimpleTorrentInfo;

import java.io.File;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Sergey.Pak
 *         Date: 8/9/13
 *         Time: 4:46 PM
 */
public class TrackerHelper {

  public static int getSeedersCount(final URI trackerURI, final String torrentHash) {
    final AtomicInteger seedersCount = new AtomicInteger(0);
    queryTracker(trackerURI, torrentHash, new AnnounceResponseListener() {
      @Override
      public void handleAnnounceResponse(int interval, int complete, int incomplete, String hexInfoHash) {
        seedersCount.set(complete);
      }

      @Override
      public void handleDiscoveredPeers(List<Peer> peers, String hexInfoHash) {
        System.out.print("");
      }
    });
    return seedersCount.get();
  }

  public static Map<Peer, BitSet> getPeersAndAvailablePieces(final URI trackerURI, final String torrentHash) {
    final Map<Peer, BitSet> result = new HashMap<Peer, BitSet>();
    final List<Peer> foundPeers = new ArrayList<Peer>();
    queryTracker(trackerURI, torrentHash, new AnnounceResponseListener() {
      @Override
      public void handleAnnounceResponse(int interval, int complete, int incomplete, String hexInfoHash) {
      }

      @Override
      public void handleDiscoveredPeers(List<Peer> peers, String hexInfoHash) {
        foundPeers.addAll(peers);
      }
    });


    for (final Peer peer : foundPeers) {
      final AtomicBoolean stop = new AtomicBoolean(false);

      final MessageListener messageListener = new MessageListener() {
        @Override
        public void handleMessage(PeerMessage msg) {
          if (msg.getType() != PeerMessage.Type.BITFIELD)
            return;
          PeerMessage.BitfieldMessage bitfieldMessage = (PeerMessage.BitfieldMessage) msg;
          result.put(peer, bitfieldMessage.getBitfield());
          stop.set(true);
        }
      };

      CommunicationListener listener = new DummyCommunicationListener() {
        @Override
        public void handleNewPeerConnection(SocketChannel s, byte[] peerId, String hexInfoHash) {
          peer.setPeerId(ByteBuffer.wrap(peerId));
          try {
            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            while (!stop.get()) {
              ConnectionUtils.readAndHandleMessage(buffer, s, stop.get(), new SimpleTorrentInfo(torrentHash), Arrays.asList(messageListener));
            }
          } catch (Exception e) {
            e.printStackTrace();
          }
        }
      };
      String id = "Tester" + UUID.randomUUID().toString().split("-")[4];

      ConnectionUtils.connect(peer, id.getBytes(), createTorrentHashObj(torrentHash), Arrays.asList(listener));
    }
    return result;
  }

  private static void queryTracker(final URI trackerURI, final String torrentHash, final AnnounceResponseListener listener) {
    try {
      String id = "Tester" + UUID.randomUUID().toString().split("-")[4];

      Peer self = new Peer(new InetSocketAddress(InetAddress.getLocalHost(), 6881), ByteBuffer.wrap(id.getBytes()));
      HTTPTrackerClient trackerClient = new HTTPTrackerClient(self, trackerURI);

      trackerClient.register(listener);
      trackerClient.announce(TrackerMessage.AnnounceRequestMessage.RequestEvent.NONE, false, new SimpleTorrentInfo(torrentHash));
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  private static TorrentHash createTorrentHashObj(final String hashString) {
    return new TorrentHash() {
      @Override
      public byte[] getInfoHash() {
        return HexBin.decode(hashString);
      }

      @Override
      public String getHexInfoHash() {
        return hashString;
      }
    };
  }


  private static class DummyCommunicationListener implements CommunicationListener {

    @Override
    public void handleNewConnection(SocketChannel s, String hexInfoHash) {

    }

    @Override
    public void handleReturnedHandshake(SocketChannel s, List<ByteBuffer> data) {

    }

    @Override
    public void handleNewData(SocketChannel s, List<ByteBuffer> data) {

    }

    @Override
    public void handleFailedConnection(Peer peer, Throwable cause) {

    }

    @Override
    public void handleNewPeerConnection(SocketChannel s, byte[] peerId, String hexInfoHash) {

    }
  }

  private static enum HelperCommands {
    seeders,
    pieces
  }

  public static void main(String[] args) throws URISyntaxException {
//    org.apache.log4j.BasicConfigurator.configure();
    String command = args[0];
    if (command.equals(HelperCommands.seeders.name())) {
      try {
        File file = new File(args[2]);
        String hash = args[2];
        int piecesCount = 0;
        if (file.isFile()){
          final Torrent torrent = Torrent.load(file);
          piecesCount = torrent.getPieceCount();
          hash = torrent.getHexInfoHash();
        }
        System.out.printf("Attempting to get seeders count for tracker %s and torrent hash %s %n", args[1], hash);
        System.out.println(getSeedersCount(new URI(args[1]), hash));
      } catch (Exception ex) {
        ex.printStackTrace();
        System.out.printf("Bad usage: java -jar torrent.jar seeders <tracker_uri> <torrent hash | path to torrent file> %n");
      }
    } else if (command.equals(HelperCommands.pieces.name())) {
      try {
//      final Map<Peer, BitSet> availablePieces = getPeersAndAvailablePieces(new URI("http://172.20.200.59:6969/announce"), "A36E6DCA1B7A0CF99BDFFAB7F765E86008D35659");
        File file = new File(args[2]);
        String hash = args[2];
        int piecesCount = 0;
        if (file.isFile()){
          final Torrent torrent = Torrent.load(file);
          piecesCount = torrent.getPieceCount();
          hash = torrent.getHexInfoHash();
        }
        System.out.printf("Attempting to get data for tracker %s and torrent hash %s %n", args[1], hash);
        final Map<Peer, BitSet> availablePieces = getPeersAndAvailablePieces(new URI(args[1]), hash);
        for (Map.Entry<Peer, BitSet> entry : availablePieces.entrySet()) {
          final BitSet bitfield = entry.getValue();
          final int truePiecesCount = piecesCount == 0 ? bitfield.size() : piecesCount;
          final BitSet reverse = new BitSet(truePiecesCount);
          reverse.set(0, truePiecesCount - 1, true);
          reverse.andNot(bitfield);
          final boolean lessThanHalf = bitfield.size() > bitfield.cardinality() * 2;
          System.out.printf("Peer %s has %d out of %d pieces.\n%s pieces: %s\n",
                  entry.getKey().toString(), bitfield.cardinality(), truePiecesCount, lessThanHalf ? "Available" : "Missing",
                  lessThanHalf ? bitfield.toString() : reverse.toString());
        }
      } catch (Exception ex) {
        ex.printStackTrace();
        System.out.printf("Bad usage: java -jar torrent.jar pieces <tracker_uri> <torrent hash | path to torrent file> %n");
      }
    }


  }
}