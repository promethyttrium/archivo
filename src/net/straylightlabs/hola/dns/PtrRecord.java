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

import java.nio.ByteBuffer;

class PtrRecord extends Record {
    private final String ptrName;

    public PtrRecord(ByteBuffer buffer, String name, Class recordClass, long ttl) {
        super(name, recordClass, ttl);
//        ByteBuffer dataBuffer = ByteBuffer.wrap(rdata);
        ptrName = readNameFromBuffer(buffer);
    }

    @Override
    public String toString() {
        return "PtrRecord{" +
                "name='" + name + '\'' +
                ", recordClass=" + recordClass +
                ", ttl=" + ttl +
                ", ptrName='" + ptrName + '\'' +
                '}';
    }
}