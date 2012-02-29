import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * CS 456 Assignment 1
 *
 * @author Frank Liu
 * Student ID 20327548
 *
 * Date 2012.2.25
 */

public class sender implements Runnable {
    private final Object mux = new Object();

    private final int ACKorEOTPacketLength = 12;

    private FileTransmitter fileTransporter;

    private final int windowSize = 10;
    private int nextSeqNum = 0;
    private int base = 0;

    private DatagramSocket monitoringSocket;
    private Map<Integer, packet> unacknowledgedPacketsCache = new HashMap<Integer, packet>();
    private UnacknowledgedPacketsRetransmitTimer retransmitTimer = new UnacknowledgedPacketsRetransmitTimer();

    private Thread ACKMonitoringThread;

    private sender (FileTransmitter transp, int mtPort) throws SocketException {
        fileTransporter = transp;

        monitoringSocket = new DatagramSocket(mtPort);

        // start monitoring thread
        ACKMonitoringThread = new Thread(this);
    }

    // sender starts transmitting file
    public void start() throws InterruptedException, IOException, Exception {
        ACKMonitoringThread.start();        // start monitoring the ACK packet from receiver
        startFileTransmitting();            // start transmitting file

        ACKMonitoringThread.join();         // wait until monitoring thread finishes
    }

    private void startFileTransmitting() throws InterruptedException, IOException, Exception {
        while (!fileTransporter.getIsFinished()) {
            synchronized (mux) {
                if (nextSeqNum < base + windowSize) {
                    // read next chuck of file and create packet wrapper
                    packet pkt = fileTransporter.readNextPacketFromFile(nextSeqNum);
                    // push it to unacknowledged packets cache
                    unacknowledgedPacketsCache.put(new Integer(nextSeqNum), pkt);

                    // send packet
                    fileTransporter.sendPacket(pkt);

                    // reset count down timer
                    if (base == nextSeqNum)
                        retransmitTimer.reschedule();

                    nextSeqNum++;
                } else {
                    mux.wait();
                }
            }
        }
    }

    // Start monitoring the ACK packet sent from receiver
    public void run() {
        byte[] receivedData = new byte[ACKorEOTPacketLength];

        try {
            while (true) {
                DatagramPacket receivedDatagramPacket = new DatagramPacket(receivedData, receivedData.length);
                monitoringSocket.receive(receivedDatagramPacket);

                packet receivedPacket = packet.parseUDPdata(receivedData);

                // received ACK packet error checking
                if (receivedPacket.getLength() != 0)
                    throw new RuntimeException("pack length is not 0");

                // update base and remove received packets from unacknowledged packets cache
                int receivedPacketSeqNum = getSeqNumFromPacketSeqNum(receivedPacket.getSeqNum());
                for (int i = base; i <= receivedPacketSeqNum; i++)
                    unacknowledgedPacketsCache.remove(new Integer(i));

                // for debug
                System.out.println("Sender: Packet Sequence " + receivedPacketSeqNum + "received, type: " + receivedPacket.getType());
                
                synchronized (mux) {
                    base = Math.max(base, receivedPacketSeqNum + 1);

                    // Update scheduled retransmitting task
                    if (base == nextSeqNum) {
                    	System.out.println("cancel the task@ run");
                        retransmitTimer.cancelTask();
                        
                    } else {
                    	System.out.println("reschedule the task@ run()");
                        retransmitTimer.reschedule();
                        
                    }

                    // base is changed, sender can continue sending packets
                    mux.notifyAll();
                }

                if (receivedPacket.getType() == 2) {
                    if (shouldFinishMonitoring())
                        break;
                    
                    throw new RuntimeException("EOT packet received while FileTransmitter is not finished");
                    
                } else if (receivedPacket.getType() != 1 && receivedPacket.getType() != 0) {
                    throw new RuntimeException("undefined packet received: type " + receivedPacket.getType());
                }
            }
        } catch (Exception ex) {
            System.out.println("sender: Received packet corrupted:" + ex.getMessage()+ "\n");
            ex.printStackTrace();
        }

        // close monitoring and transmitting socket
        monitoringSocket.close();
        fileTransporter.closeTransmitterSocket();
    }

    private int getSeqNumFromPacketSeqNum(int packetSeqNum) {
        int num = base - base % 32 + packetSeqNum;
        return (num >= base)? num : num + 32;
    }

    private boolean shouldFinishMonitoring() {
        synchronized (mux) {
            return fileTransporter.getIsFinished() && nextSeqNum == base;
        }
    }

    private class UnacknowledgedPacketsRetransmitTimer extends TimerTask {
        private final int countDownDelay = 1000;
        private Timer timer = new Timer();

        public UnacknowledgedPacketsRetransmitTimer() {
            super();
        }

        public void reschedule() {
            cancelTask();
        	System.out.println("reschedule the task@ reschedule()");
            timer.schedule(new UnacknowledgedPacketsRetransmitTimer(), countDownDelay);  //Jason
        }

        public void cancelTask() {
        	System.out.println("@cancelTask(): canceling1");
        	cancel();
            System.out.println("@cancelTask(): canceling2");
            //timer.cancel();
        }

        public void run() {
            // resend all unacknowledged packet
        	System.out.println("@unack timertask run(), see how many times it runs");
            for(Map.Entry<Integer, packet> unacknowledgedPacket : unacknowledgedPacketsCache.entrySet()) {
                try {
                    fileTransporter.sendPacket(unacknowledgedPacket.getValue());
                } catch (IOException ex) {
                    System.out.println("sender: UnacknowledgedPacketsRetransmitTimer: packet I/O error " + ex.getMessage());
                }
            }
        }
    }

    public static void main(String [ ] args) {

        try {
            // Check if the input format is valid, otherwise print usage description
            if (args.length < 4) {
               String str = "Usage:\n"
                       + "\tjava sender | <arguments>\n\n"
                       + "<arguments>:\n"
                       + "\t<host address of the network emulator>\n"
                       + "\t<UDP port number used by the emulator to receive data from the sender>\n"
                       + "\t<UDP port number used by the sender to receive ACKs from the emulator>\n"
                       + "\t<name of the file to be transferred>";
               throw new RuntimeException(str);
            }

            InetAddress emulatorAdd = InetAddress.getByName(args[0]);
            int emulatorPort = Integer.parseInt(args[1]);
            int senderPort = Integer.parseInt(args[2]);
            File fileToBeTransferred = new File(args[3]);

            if (!fileToBeTransferred.canRead()) {
                String str = "sender: Given file is not readable";
                throw new RuntimeException(str);
            }

            FileTransmitter fileTransporter = new FileTransmitter(emulatorAdd, emulatorPort, fileToBeTransferred);
            sender fileSender = new sender(fileTransporter, senderPort);
            fileSender.start();
            
        } catch (UnknownHostException ex) {
            System.out.println("sender: Can't resolve the host address of the network emulator: " + ex.getMessage());
        } catch (NumberFormatException ex) {
            System.out.println("sender: Can't parse the given port number: " + ex.getMessage());
        } catch (RuntimeException ex) {
            System.out.println(ex.getMessage());
        } catch (FileNotFoundException ex) {
            System.out.println("sender: Given file not found: " + ex.getMessage());
        } catch (InterruptedException ex) {
            System.out.println("sender: Process interrupted unexpectedly: " + ex.getMessage());
        } catch (SocketException ex) {
            System.out.println("sender: Could not create DatagramSocket (on given port) " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("sender: I/O error" + ex.getMessage());
        } catch (Exception ex) {
            System.out.println("sender: packet.createPacket: " + ex.getMessage());
        }
    }
}

class FileTransmitter {
    private InetAddress emuAdd;         // host address of the network emulator
    private int emuPort;                // UDP port number used by the emulator to receive data from the sender
    private FileInputStream fileStream; // file to be transferred

    private DatagramSocket transmitterSocket;

    private boolean isFinished = false;
    private final int fileBufferSize = 500;
    private byte [] fileBuff = new byte [fileBufferSize];

    public FileTransmitter (InetAddress emulatorAdd, int emulatorPort, File fileToBeTransferred) throws FileNotFoundException, SocketException {
        emuAdd = emulatorAdd;
        emuPort = emulatorPort;

        fileStream = new FileInputStream(fileToBeTransferred);
        transmitterSocket = new DatagramSocket();
    }

    public void sendPacket(packet p) throws IOException {
        // send packet as byte array field of java DatagramPacket
        byte[] sendData = p.getUDPdata();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, emuAdd, emuPort);

        transmitterSocket.send(sendPacket);
        
        // for debug
        System.out.println("Sender: Packet Sequence " + p.getSeqNum() + "send, type: ");
    }

    public void closeTransmitterSocket() {
        transmitterSocket.close();
    }

    public packet readNextPacketFromFile (int nextSeqNum) throws IOException, Exception {
        // read data to byte buffer

        int ret = fileStream.read(fileBuff);

        // Last read just finishes reading the file
        if (ret == -1) {
            isFinished = true;
            fileStream.close();
            return packet.createEOT(nextSeqNum);
        }

        return packet.createPacket(nextSeqNum, new String(fileBuff, 0, ret));
    }

    public boolean getIsFinished() {
        return isFinished;
    }
}