/*
 * Copyright 2015 Todd Kulesza <todd@dropline.net>.
 *
 * This file is part of Archivo.
 *
 * Archivo is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Archivo is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Archivo.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.straylightlabs.hola.dns;


import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.List;

public class Response extends Message {
    private final List<Record> records;
    private int numQuestions;
    private int numAnswers;
    private int numNameServers;
    private int numAdditionalRecords;

    private final static int QR_MASK = 0x8000;
    private final static int OPCODE_MASK = 0x7800;
    private final static int RCODE_MASK = 0xF;

    public static Response createFrom(DatagramPacket packet) {
        Response response = new Response(packet);
        System.out.println("Response = \n" + response.dumpBuffer());
        response.parseRecords();
        return response;
    }

    private Response() {
        records = new ArrayList<>();
    }

    private Response(DatagramPacket packet) {
        this();
        byte[] dstBuffer = buffer.array();
        System.arraycopy(packet.getData(), packet.getOffset(), dstBuffer, 0, packet.getLength());
        buffer.limit(packet.getLength());
        buffer.position(0);

    }

    private void parseRecords() {
        parseHeader();
        System.out.println("numQuestions: " + numQuestions);
        for (int i = 0; i < numQuestions; i++) {
            Question question = Question.fromBuffer(buffer);
            System.out.println("Question: " + question);
        }
        System.out.println("numAnswers: " + numAnswers);
        for (int i = 0; i < numAnswers; i++) {
            Record record = Record.fromBuffer(buffer);
            System.out.println("Answer: " + record);
        }
        System.out.println("numNameServers: " + numNameServers);
        for (int i = 0; i < numNameServers; i++) {
            Record record = Record.fromBuffer(buffer);
            System.out.println("Name server: " + record);
        }
        System.out.println("numAdditionalRecords: " + numAdditionalRecords);
        for (int i = 0; i < numAdditionalRecords; i++) {
            Record record = Record.fromBuffer(buffer);
            System.out.println("Additional record: " + record);
        }
    }

    private void parseHeader() {
        int id = readUnsignedShort();
        int codes = readUnsignedShort();
        if ((codes & QR_MASK) != QR_MASK) {
            // FIXME create a custom Exception for DNS errors
            throw new IllegalArgumentException("Packet is not a DNS response");
        }
        if ((codes & OPCODE_MASK) != 0) {
            throw new IllegalArgumentException("mDNS response packets can't have OPCODE values");
        }
        if ((codes & RCODE_MASK) != 0) {
            throw new IllegalArgumentException("mDNS response packets can't have RCODE values");
        }
        numQuestions = readUnsignedShort();
        numAnswers = readUnsignedShort();
        numNameServers = readUnsignedShort();
        numAdditionalRecords = readUnsignedShort();
    }
}