package gg;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.memory.DataInputViewStreamWrapper;
import org.apache.flink.core.memory.DataOutputViewStreamWrapper;
import org.apache.flink.runtime.taskmanager.TaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.*;

public class CFLManager {

	protected static final Logger LOG = LoggerFactory.getLogger(CFLManager.class);

	private static boolean logCoord = false;

	private static CFLManager sing = null;
	public static CFLManager getSing() {return sing;}

	private static final int port = 4444;

	public static byte tmId = -1;
	public static int numAllSlots = -1;
	public static int numTaskSlotsPerTm = -1;

//	public static void create(TaskManager tm) {
//		sing = new CFLManager(tm);
//	}

	public static void create(TaskManager tm, String[] hosts, boolean coordinator) {
		sing = new CFLManager(tm, hosts, coordinator);
	}


//	public CFLManager(TaskManager tm) {
//		// local execution
//		this(tm, new String[]{}, true);
//	}

	public CFLManager(TaskManager tm, String[] hosts, boolean coordinator) {
		this.tm = tm;
		this.hosts = hosts;
		this.coordinator = coordinator;
		connReaders = new ConnReader[hosts.length];
		recvRemoteAddresses = new SocketAddress[hosts.length];

		connAccepter = new ConnAccepter(); //thread

		senderSockets = new Socket[hosts.length];
		senderStreams = new OutputStream[hosts.length];
		senderDataOutputViews = new DataOutputViewStreamWrapper[hosts.length];

		cflSendSeqNum = 0;

		jobCounter = 0;

		createSenderConnections();
	}

	private TaskManager tm;

	private boolean coordinator;

	private String[] hosts;
	private ConnAccepter connAccepter;
	private ConnReader[] connReaders;
	private SocketAddress[] recvRemoteAddresses;

	private Socket[] senderSockets;
	private OutputStream[] senderStreams;
	private DataOutputViewStreamWrapper[] senderDataOutputViews;

	private volatile boolean allSenderUp = false;
	private volatile boolean allIncomingUp = false;

	private List<Integer> tentativeCFL = new ArrayList<>(); // ez lehet lyukas, ha nem sorrendben erkeznek meg az elemek
	private List<Integer> curCFL = new ArrayList<>(); // ez sosem lyukas

	private List<CFLCallback> callbacks = new ArrayList<>();

	private int terminalBB = -1;

	private volatile int cflSendSeqNum = 0;

	private final Object clientLock = new Object(); // TODO: rename to msgSendLock

	private volatile int jobCounter = -10;

	private ResetThread resetThread;

	public JobID getJobID() {
		return jobID;
	}

	public void setJobID(JobID jobID) {
		LOG.info("CFLManager.setJobID to '" + jobID + "'");
		if (this.jobID != null && !this.jobID.equals(jobID) && jobID != null) {
			throw new RuntimeException("Csak egy job futhat egyszerre. (old: " + this.jobID + ", new: " + jobID + ")");
		}
		this.jobID = jobID;
	}

	private JobID jobID = null;

	private void createSenderConnections() {
		final int timeout = 500;
		int i = 0;
		for (String host : hosts) {
			try {
				Socket socket;
				while(true) {
					try {
						socket = new Socket();
						socket.setPerformancePreferences(0,1,0);
						LOG.info("Connecting sender connection to " + host + ".");
						socket.connect(new InetSocketAddress(host, port), timeout);
						LOG.info("Sender connection connected to  " + host + ".");
						break;
					} catch (SocketTimeoutException exTimeout) {
						LOG.info("Sender connection to            " + host + " timed out, retrying...");
					} catch (ConnectException ex) {
						LOG.info("Sender connection to            " + host + " was refused, retrying...");
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) {
							throw new RuntimeException(e);
						}
					} catch (IOException e) {
						LOG.info("Sender connection to            " + host + " caused an IOException, retrying... " + e);
						try {
							Thread.sleep(500);
						} catch (InterruptedException e2) {
							throw new RuntimeException(e2);
						}
					}
				}
				senderSockets[i] = socket; //new Socket(host, port);
				senderStreams[i] = socket.getOutputStream();
				senderDataOutputViews[i] = new DataOutputViewStreamWrapper(senderStreams[i]);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			i++;
		}
		LOG.info("All sender connections are up.");
		allSenderUp = true;
	}

	private void sendElement(CFLElement e) {
		for (int i = 0; i<hosts.length; i++) {
			try {
				msgSer.serialize(new Msg(jobCounter, e), senderDataOutputViews[i]);
				senderStreams[i].flush();
			} catch (IOException e1) {
				throw new RuntimeException(e1);
			}
		}
	}

	private class ConnAccepter implements Runnable {

		Thread thread;

		public ConnAccepter() {
			thread = new Thread(this, "ConnAccepter");
			thread.start();
		}

		@Override
		public void run() {
			ServerSocket serverSocket;
			try {
				serverSocket = new ServerSocket(port);
				int i = 0;
				while(i < hosts.length) {
					LOG.info("Listening for incoming connections " + i);
					Socket socket = serverSocket.accept();
					SocketAddress remoteAddr = socket.getRemoteSocketAddress();
					LOG.info("Got incoming connection " + i + " from " + remoteAddr);
					recvRemoteAddresses[i] = remoteAddr;
					connReaders[i] = new ConnReader(socket, i);
					i++;
				}
				LOG.info("All incoming connections connected");
				allIncomingUp = true;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private class ConnReader implements Runnable {

		Thread thread;

		Socket socket;

		int connID;

		public ConnReader(Socket socket, int connID) {
			this.socket = socket;
			this.connID = connID;
			thread = new Thread(this, "ConnReader_" + connID);
			thread.start();
		}

		@Override
		public void run() {
			try {
				try {
					InputStream ins = socket.getInputStream();
					DataInputViewStreamWrapper divsw = new DataInputViewStreamWrapper(ins);
					while (true) {
						Msg msg = msgSer.deserialize(divsw);
						synchronized (CFLManager.this) {
							if (logCoord) LOG.info("Got " + msg);

							if (msg.jobCounter < jobCounter) {
								// Mondjuk ebbol itt lehet baj, ha vki meg nem kapta meg nem kapta meg a voteStop-hoz eljutashoz szukseges msg-ket.
								// De most van az a sleep(500) a reset elott, remelhetoleg az biztositja, hogy ne jussunk ide.
								LOG.info("Old msg, ignoring (msg.jobCounter = " + msg.jobCounter + ", jobCounter = " + jobCounter + ")");
								continue;
							}
							while (msg.jobCounter > jobCounter) {
								LOG.info("Too new msg, waiting (msg.jobCounter = " + msg.jobCounter + ", jobCounter = " + jobCounter + ")"); // Ilyenkor valojaban a resetre varunk
								Thread.sleep(100);
							}

							if (msg.cflElement != null) {
								addTentative(msg.cflElement.seqNum, msg.cflElement.bbId); // will do the callbacks
							} else if (msg.consumed != null) {
								assert coordinator;
								consumedRemote(msg.consumed.bagID, msg.consumed.numElements, msg.consumed.subtaskIndex, msg.consumed.opID);
							} else if (msg.produced != null) {
								assert coordinator;
								producedRemote(msg.produced.bagID, msg.produced.inpIDs, msg.produced.numElements, msg.produced.para, msg.produced.subtaskIndex, msg.produced.opID);
							} else if (msg.closeInputBag != null) {
								closeInputBagRemote(msg.closeInputBag.bagID, msg.closeInputBag.opID);
							}
						}
					}
				} catch (EOFException e) {
					// This happens when the other TM shuts down. No need to throw a RuntimeException here, as we are shutting down anyway.
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			} catch (Throwable e) {
				e.printStackTrace();
				LOG.error(e.getStackTrace().toString());
				Runtime.getRuntime().halt(200);
			}
		}
	}

	private synchronized void addTentative(int seqNum, int bbId) {
		while (seqNum >= tentativeCFL.size()) {
			tentativeCFL.add(null);
			cflSendSeqNum = Math.max(cflSendSeqNum, seqNum + 1);
		}
		assert tentativeCFL.get(seqNum) == null; // (kivenni, ha lesz az UDP-s dolog)
		tentativeCFL.set(seqNum, bbId);

		for (int i = curCFL.size(); i < tentativeCFL.size(); i++) {
			Integer t = tentativeCFL.get(i);
			if (t == null)
				break;
			curCFL.add(t);
			LOG.info("Adding BBID " + t + " to CFL");
			notifyCallbacks();
			// szoval minden elemnel kuldunk kulon, tehat a subscribereknek sok esetben eleg lehet az utolso elemet nezni
		}
	}

	private synchronized void notifyCallbacks() {
		for (CFLCallback cb: callbacks) {
			cb.notify(curCFL);
		}

		assert callbacks.size() == 0 || terminalBB != -1; // A drivernek be kell allitania a job elindulasa elott. Viszont ebbe a fieldbe a BagOperatorHost.setup-ban kerul.
		if (curCFL.get(curCFL.size() - 1) == terminalBB) {
			assert terminalBB != -1;
			// We need to copy, because notifyTerminalBB might call unsubscribe, which would lead to a ConcurrentModificationException
			ArrayList<CFLCallback> origCallbacks = new ArrayList<>(callbacks);
			for (CFLCallback cb: origCallbacks) {
				cb.notifyTerminalBB();
			}
		}
	}


	// --------------------------------------------------------

	// A helyi TM-ben futo operatorok hivjak
	public void appendToCFL(int bbId) {
		synchronized (clientLock) {
			assert tentativeCFL.size() == curCFL.size(); // azaz ilyenkor nem lehetnek lyukak

			LOG.info("Adding " + bbId + " to CFL (appendToCFL)");

			//tentativeCFL.add(bbId);
			//curCFL.add(bbId);
			//sendElement(new CFLElement(curCFL.size()-1, bbId));
			//notifyCallbacks();

			sendElement(new CFLElement(cflSendSeqNum++, bbId));
		}
	}

	public synchronized void subscribe(CFLCallback cb) {
		LOG.info("CFLManager.subscribe");
		assert allIncomingUp && allSenderUp;

		// Maybe there could be a waitForReset here

		callbacks.add(cb);

		// Egyenkent elkuldjuk a notificationt mindegyik eddigirol
		List<Integer> tmpCfl = new ArrayList<>();
		for(Integer x: curCFL) {
			tmpCfl.add(x);
			cb.notify(tmpCfl);
		}

		assert terminalBB != -1; // a drivernek be kell allitania a job elindulasa elott
		if (curCFL.size() > 0 && curCFL.get(curCFL.size() - 1) == terminalBB) {
			cb.notifyTerminalBB();
		}

		for(CloseInputBag cib: closeInputBags) {
			cb.notifyCloseInput(cib.bagID, cib.opID);
		}
	}

	public synchronized void unsubscribe(CFLCallback cb) {
		LOG.info("CFLManager.unsubscribe");
		callbacks.remove(cb);

		if (callbacks.isEmpty()) {
			LOG.info("tm.CFLVoteStop();");
			tm.CFLVoteStop();
			setJobID(null);
			resetThread = new ResetThread();
		}
	}

	private class ResetThread implements Runnable {

		Thread thread;

		public ResetThread() {
			thread = new Thread(this, "ResetThread");
			thread.start();
		}

		@Override
		public void run() {
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				throw new RuntimeException();
			}
			reset();
		}
	}

	private synchronized void reset() {
		LOG.info("Resetting CFLManager.");

		assert callbacks.size() == 0;

		tentativeCFL.clear();
		curCFL.clear();

		cflSendSeqNum = 0;

		bagStatuses.clear();
		bagConsumedStatuses.clear();

		closeInputBags.clear();

		jobCounter++;
	}

	public synchronized void specifyTerminalBB(int bbId) {
		LOG.info("specifyTerminalBB: " + bbId);
		terminalBB = bbId;
	}

    // --------------------------------------------------------


    private static final class BagStatus {

        public int numProduced = 0;
		public boolean produceClosed = false;

		public Set<Integer> producedSubtasks = new HashSet<>();

		public Set<BagID> inputs = new HashSet<>();
		public Set<BagID> inputTo = new HashSet<>();
		public Set<Integer> consumedBy = new HashSet<>();
    }

	private static final class BagConsumptionStatus {

		public int numConsumed = 0;
		public boolean consumeClosed = false;

		public Set<Integer> consumedSubtasks = new HashSet<>();
	}

    private final Map<BagID, BagStatus> bagStatuses = new HashMap<>();

	private final Map<BagIDAndOpID, BagConsumptionStatus> bagConsumedStatuses = new HashMap<>();

	private final List<CloseInputBag> closeInputBags = new ArrayList<>();

    // kliens -> coordinator
    public void consumedLocal(BagID bagID, int numElements, int subtaskIndex, int opID) {
		synchronized (clientLock) {
	//		if (coordinator) {
	//			consumedRemote(bagID, numElements, subtaskIndex, opID);
	//		} else {
				try {
					msgSer.serialize(new Msg(jobCounter, new Consumed(bagID, numElements, subtaskIndex, opID)), senderDataOutputViews[0]);
					senderStreams[0].flush();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
	//		}
		}
    }

    private synchronized void consumedRemote(BagID bagID, int numElements, int subtaskIndex, int opID) {
		if (logCoord) LOG.info("consumedRemote(bagID = " + bagID + ", numElements = " + numElements + ", opID = " + opID + ")");

		// Get or init BagStatus
		BagStatus s = bagStatuses.get(bagID);
		if (s == null) {
			s = new BagStatus();
			bagStatuses.put(bagID, s);
		}

		// Get or init BagConsumptionStatus
		BagIDAndOpID key = new BagIDAndOpID(bagID, opID);
		BagConsumptionStatus c = bagConsumedStatuses.get(key);
		if (c == null) {
			c = new BagConsumptionStatus();
			bagConsumedStatuses.put(key, c);
		}

		assert !c.consumeClosed;

		s.consumedBy.add(opID);

		c.consumedSubtasks.add(subtaskIndex);

		c.numConsumed += numElements;

		checkForClosingConsumed(bagID, s, c, opID);

		for (BagID b: bagStatuses.get(bagID).inputTo) {
			// azert jo itt a -1, mert ilyenkor biztosan nem source
			checkForClosingProduced(b, bagStatuses.get(b), -1, opID);
		}
    }

    private void checkForClosingConsumed(BagID bagID, BagStatus s, BagConsumptionStatus c, int opID) {
		if (s.produceClosed) {
			assert c.numConsumed <= s.numProduced; // (ennek belul kell lennie az if-ben mert kivul a reordering miatt nem biztos, hogy igaz)
			if (c.numConsumed == s.numProduced) {
				if (logCoord) LOG.info("checkForClosingConsumed(" + bagID + ", opID = " + opID + "): consumeClosed, because numConsumed = " + c.numConsumed + ", numProduced = " + s.numProduced);
				c.consumeClosed = true;
				closeInputBagLocal(bagID, opID);
			} else {
				if (logCoord) LOG.info("checkForClosingConsumed(" + bagID + ", opID = " + opID + "): needMore, because numConsumed = " + c.numConsumed + ", numProduced = " + s.numProduced);
			}
		}
	}

    // kliens -> coordinator
	public void producedLocal(BagID bagID, BagID[] inpIDs, int numElements, int para, int subtaskIndex, int opID) {
		synchronized (clientLock) {
			assert inpIDs.length <= 2; // ha 0, akkor BagSource

	//		if (coordinator) {
	//			producedRemote(bagID, inpIDs, numElements, para, subtaskIndex, opID);
	//		} else {
				try {
					msgSer.serialize(new Msg(jobCounter, new Produced(bagID, inpIDs, numElements, para, subtaskIndex, opID)), senderDataOutputViews[0]);
					senderStreams[0].flush();
				} catch (IOException e) {
					throw new RuntimeException();
				}
	//		}
		}
    }

    private synchronized void producedRemote(BagID bagID, BagID[] inpIDs, int numElements, int para, int subtaskIndex, int opID) {
		if (logCoord) LOG.info("producedRemote(bagID = " + bagID + ", numElements = " + numElements + ", opID = " + opID + ")");

		// Get or init BagStatus
		BagStatus s = bagStatuses.get(bagID);
		if (s == null) {
			s = new BagStatus();
			bagStatuses.put(bagID, s);
		}

		assert !s.produceClosed;

		// Add to s.inputs, and add to the inputTos of the inputs
		for (BagID inp: inpIDs) {
			s.inputs.add(inp);
			bagStatuses.get(inp).inputTo.add(bagID);
		}
		assert s.inputs.size() <= 2;

		// Add to s.numProduced
		s.numProduced += numElements;

		// Add to s.producedSubtasks
		assert !s.producedSubtasks.contains(subtaskIndex);
		s.producedSubtasks.add(subtaskIndex);

		checkForClosingProduced(bagID, s, para, opID);

		for (Integer copID: s.consumedBy) {
			checkForClosingConsumed(bagID, s, bagConsumedStatuses.get(new BagIDAndOpID(bagID, copID)), copID);
		}
    }

    private void checkForClosingProduced(BagID bagID, BagStatus s, int para, int opID) {
		if (s.inputs.size() == 0) {
			// source, tehat mindenhonnan varunk
			assert para != -1;
			int totalProducedMsgs = s.producedSubtasks.size();
			assert totalProducedMsgs <= para;
			if (totalProducedMsgs == para) {
				if (logCoord) LOG.info("produceClose for bag " + bagID + " at op " + opID);
				s.produceClosed = true;
			}
		} else {
			boolean needMore = false;
			// Ebbe rakjuk ossze az inputok consumedSubtasks-jait
			Set<Integer> needProduced = new HashSet<>();
			for (BagID inp: s.inputs) {
				BagConsumptionStatus bcs = bagConsumedStatuses.get(new BagIDAndOpID(inp, opID));
				if (bcs != null) {
					if (!bcs.consumeClosed) {
						if (logCoord) LOG.info("checkForClosingProduced(" + bagID + ", " + s + ", opID = " + opID + "): needMore, because !bcs.consumeClosed");
						needMore = true;
						break;
					}
					needProduced.addAll(bcs.consumedSubtasks);
				} else {
					assert false;
					// Jaa, vagy az van esetleg, hogy nem mindig a termeszetes sorrendben jon a produced es a consumed?

					// This can happen if we have a binary operator that sends produced while it haven't yet consumed from one of its inputs.
					// Hm, but actually I don't think I have such an operator at the moment.
					assert s.inputs.size() > 1;
					needMore = true;
				}
			}
			if (!needMore) {
				int needed = needProduced.size();
				int actual = s.producedSubtasks.size();
				assert actual <= needed; // This should be true, because we have already checked consumeClose above
				if (actual < needed) {
					if (logCoord) LOG.info("checkForClosingProduced(" + bagID + ", " + s + ", opID = " + opID + "): needMore, because actual = " + actual + ", needed = " + needed);
					needMore = true;
				}
			}
			if (!needMore) {
				if (logCoord) LOG.info("produceClose for bag " + bagID + " at op " + opID);
				s.produceClosed = true;
			}
		}
	}

    // A coordinator a local itt. Az operatorok inputjainak a close-olasat valtja ez ki.
    private synchronized void closeInputBagLocal(BagID bagID, int opID) {
		synchronized (clientLock) { // Azert kell itt ez is, mert kulonben a senderStream-ekben osszekavarodhatnak az uzenetek
			assert coordinator;

			//closeInputBagRemote(bagID, opID);

			for (int i = 0; i < hosts.length; i++) {
				try {
					msgSer.serialize(new Msg(jobCounter, new CloseInputBag(bagID, opID)), senderDataOutputViews[i]);
					senderStreams[i].flush();
				} catch (IOException e1) {
					throw new RuntimeException(e1);
				}
			}
		}
    }

	// (runs on client)
    private synchronized void closeInputBagRemote(BagID bagID, int opID) {
		if (logCoord) LOG.info("closeInputBagRemote(" + bagID + ", " + opID +")");

		closeInputBags.add(new CloseInputBag(bagID, opID));

		ArrayList<CFLCallback> origCallbacks = new ArrayList<>(callbacks);
		for (CFLCallback cb: origCallbacks) {
			cb.notifyCloseInput(bagID, opID);
		}
    }

    // --------------------------------

    public static class Msg {

		public int jobCounter;

		// These are nullable, and exactly one should be non-null
		public CFLElement cflElement;
		public Consumed consumed;
		public Produced produced;
		public CloseInputBag closeInputBag;

		public Msg() {}

		public Msg(int jobCounter, CFLElement cflElement) {
			this.jobCounter = jobCounter;
			this.cflElement = cflElement;
		}

		public Msg(int jobCounter, Consumed consumed) {
			this.jobCounter = jobCounter;
			this.consumed = consumed;
		}

		public Msg(int jobCounter, Produced produced) {
			this.jobCounter = jobCounter;
			this.produced = produced;
		}

		public Msg(int jobCounter, CloseInputBag closeInputBag) {
			this.jobCounter = jobCounter;
			this.closeInputBag = closeInputBag;
		}

		@Override
		public String toString() {
			return "Msg{" +
					"jobCounter=" + jobCounter +
					", cflElement=" + cflElement +
					", consumed=" + consumed +
					", produced=" + produced +
					", closeInputBag=" + closeInputBag +
					'}';
		}
	}

	private static final TypeSerializer<Msg> msgSer = TypeInformation.of(Msg.class).createSerializer(new ExecutionConfig());

	public static class Consumed {

		public BagID bagID;
		public int numElements;
		public int subtaskIndex;
		public int opID;

		public Consumed() {}

		public Consumed(BagID bagID, int numElements, int subtaskIndex, int opID) {
			this.bagID = bagID;
			this.numElements = numElements;
			this.subtaskIndex = subtaskIndex;
			this.opID = opID;
		}

		@Override
		public String toString() {
			return "Consumed{" +
					"bagID=" + bagID +
					", numElements=" + numElements +
					", subtaskIndex=" + subtaskIndex +
					", opID=" + opID +
					'}';
		}
	}

	public static class Produced {

		public BagID bagID;
		public BagID[] inpIDs;
		public int numElements;
		public int para;
		public int subtaskIndex;
		public int opID;

		public Produced() {}

		public Produced(BagID bagID, BagID[] inpIDs, int numElements, int para, int subtaskIndex, int opID) {
			this.bagID = bagID;
			this.inpIDs = inpIDs;
			this.numElements = numElements;
			this.para = para;
			this.subtaskIndex = subtaskIndex;
			this.opID = opID;
		}

		@Override
		public String toString() {
			return "Produced{" +
					"bagID=" + bagID +
					", inpIDs=" + Arrays.toString(inpIDs) +
					", numElements=" + numElements +
					", para=" + para +
					", subtaskIndex=" + subtaskIndex +
					", opID=" + opID +
					'}';
		}
	}

	public static class CloseInputBag {

		public BagID bagID;
		public int opID;

		public CloseInputBag() {}

		public CloseInputBag(BagID bagID, int opID) {
			this.bagID = bagID;
			this.opID = opID;
		}

		@Override
		public String toString() {
			return "CloseInputBag{" +
					"bagID=" + bagID +
					", opID=" + opID +
					'}';
		}
	}

	// --------------------------------
}
