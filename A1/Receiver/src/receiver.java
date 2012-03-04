import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

/**
 * CS 456 Assignment 1
 *
 * @author Weixin Liu, Shuofeng Liu
 * Student ID 20327548, 20340988
 *
 * Date 2012.2.25
 */

public class receiver {
    private InetAddress emuAdd;             // hostname for the network emulator
    private int emuPort;                    // UDP port number used by the link emulator to receive ACKs from the receiver
    private FileOutputStream fileStream;    // name of the file into which the received data is written

    private final int dataPacketLength = 512;

    private int expectedSeqNum = 0;
    private DatagramSocket monitoringSocket;
    private packet lastSentInOrderACKPacket;

    // generating arrival.log files for recording the sequence numbers of all the data packets that the receiver receives
    private BufferedWriter receivedPacketsSeqNumWriter;

    private receiver (InetAddress emulatorAdd, int emulatorPort, int receiverPort, File fileToWriteTo) 
            throws FileNotFoundException, SocketException, IOException  {
        emuAdd = emulatorAdd;
        emuPort = emulatorPort;
        receivedPacketsSeqNumWriter = new BufferedWriter(new FileWriter("arrival.log"));

        fileStream = new FileOutputStream(fileToWriteTo);
        monitoringSocket = new DatagramSocket(receiverPort);
    }

    public void startFileReceiving() throws RuntimeException, IOException, Exception {
        byte[] receiveData = new byte[dataPacketLength];

        while (true) {
            // receive packet from sender
            DatagramPacket receiveDatagramPacket = new DatagramPacket(receiveData, receiveData.length);
            monitoringSocket.receive(receiveDatagramPacket);

            packet rcvPacket = packet.parseUDPdata(receiveData);

            checkReceivedPacketValidity(rcvPacket);

            // received unexpected packet
            if (!isReceivingExpectedPacket(rcvPacket)) {
                // default: (re)send last-sent-in-order ACK packet
                if (expectedSeqNum != 0)
                    sendPacket(lastSentInOrderACKPacket);
            } else {
                // for debug
                // System.out.println("receiver: packet " + rcvPacket.getSeqNum() + " received");
                
                if (rcvPacket.getType() == 1) {
                    // update last-sent-in-order ACK packet
                    lastSentInOrderACKPacket = packet.createACK(expectedSeqNum++);

                    // recording packet number of received data packet in ack.log
                    receivedPacketsSeqNumWriter.write(String.format("%d\n", rcvPacket.getSeqNum()));

                    // write received packet to file
                    writePacketToFile (rcvPacket);
                    
                    // send last-sent-in-order ACK packet
                    sendPacket(lastSentInOrderACKPacket);
                    
                    // for debug
                    // System.out.println("receiver: packet " + lastSentInOrderACKPacket.getSeqNum() + " send");
                } else if (rcvPacket.getType() == 2) {
                    sendPacket(rcvPacket);
                    break;
                }
            }
        }

        receivedPacketsSeqNumWriter.close();
        fileStream.close();
        monitoringSocket.close();
    }

    private void checkReceivedPacketValidity(packet p) throws RuntimeException {
        if (p.getType() == 1) {
            // data packet
            if (p.getLength() <= 0)
                throw new RuntimeException("receiver: Received data packet's data length is 0");

        } else if (p.getType() == 2) {
            // EOT packet
            if (p.getLength() > 0)
                throw new RuntimeException("receiver: EOT packet corrupted");
        } else {
            throw new RuntimeException("receiver: Undefined packet received: type " + p.getType());
        }
    }

    private void writePacketToFile (packet rcvPacket) throws IOException {
        fileStream.write(rcvPacket.getData());
    }

    private void sendPacket(packet p) throws IOException {
        // send packet as byte array field of java DatagramPacket
        byte[] sendData = p.getUDPdata();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, emuAdd, emuPort);

        monitoringSocket.send(sendPacket);
    }

    private boolean isReceivingExpectedPacket(packet p) {
        return p.getSeqNum() == expectedSeqNum % 32;
    }

    public static void main(String [ ] args) {

        try {
            // Check if the input format is valid, otherwise print usage description
            if (args.length < 4) {
               String str = "Usage:\n"
                   + "\tjava receiver | <arguments>\n\n"
                   + "<arguments>:\n"
                   + "\t<hostname for the network emulator>\n"
                   + "\t<UDP port number used by the link emulator to receive ACKs from the receiver>\n"
                   + "\t<UDP port number used by the receiver to receive data from the emulator>\n"
                   + "\t<name of the file into which the received data is written>";
               throw new RuntimeException(str);
            }

            InetAddress emulatorAdd = InetAddress.getByName(args[0]);
            int emulatorPort = Integer.parseInt(args[1]);
            int receiverPort = Integer.parseInt(args[2]);
            File fileToWriteTo = new File(args[3]);

            // create new if give file does not exist
            if (!fileToWriteTo.exists())
                fileToWriteTo.createNewFile();

            // throw exception if given file is not writable
            if (!fileToWriteTo.canWrite()) {
                String str = "receiver: Given file is not writable";
                throw new RuntimeException(str);
            }

            receiver fileReceiver = new receiver(emulatorAdd, emulatorPort, receiverPort, fileToWriteTo);
            fileReceiver.startFileReceiving();

        } catch (UnknownHostException ex) {
            System.out.println("receiver: Can't resolve the host address of the network emulator: " + ex.getMessage());
        } catch (RuntimeException ex) {
            System.out.println(ex.getMessage());
        } catch (FileNotFoundException ex) {
            System.out.println("receiver: Given file not found: " + ex.getMessage());
        } catch (SocketException ex) {
            System.out.println("receiver: Could not create DatagramSocket (on given port) " + ex.getMessage());
        } catch (IOException ex) {
            System.out.println("receiver: File I/O error" + ex.getMessage());
        } catch (Exception ex) {
            System.out.println("receiver: packet.createPacket: " + ex.getMessage());
        }
    }
}
