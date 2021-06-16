/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2013
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4che3.imageio.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageInputStreamImpl;

import org.dcm4che3.data.BulkData;
import org.dcm4che3.data.Fragments;
import org.dcm4che3.data.Tag;
import org.dcm4che3.util.ByteUtils;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 *
 */
public class SegmentedImageInputStream extends ImageInputStreamImpl {

    private final ImageInputStream stream;
    private final boolean autoExtend;
    private long[] segmentPositionsList;
    private int[] segmentLengths;
    private int curSegment;
    private long curSegmentEnd;
    private byte[] header = new byte[8];

    public SegmentedImageInputStream(ImageInputStream stream,
                                     long[] segmentPositionsList, int[] segmentLengths)
                    throws IOException {
        this.stream = stream;
        this.segmentPositionsList = segmentPositionsList.clone();
        this.segmentLengths = segmentLengths.clone();
        this.autoExtend = false;
        seek(0);
    }

    public SegmentedImageInputStream(ImageInputStream stream, long pos, int len, boolean autoExtend)
            throws IOException {
        this.stream = stream;
        this.segmentPositionsList = new long[]{ pos };
        this.segmentLengths = new int[]{ len };
        this.autoExtend = autoExtend;
        seek(0);
    }

    public static SegmentedImageInputStream ofFrame(ImageInputStream iis, Fragments fragments, int index, int frames)
            throws IOException {
        if (fragments.size() < frames + 1) {
            // We don't know the transfer syntax, so we just have to trust that we've previously confirmed that this
            // transfer syntax supports multi-fragment frames if we have more fragments than (frames+1).
            throw new UnsupportedOperationException(
                    "Number of Fragments [" + fragments.size()
                            + "] != Number of Frames [" + frames + "] + 1");
        }
        if (fragments.size() == frames + 1) {
            // We have exactly one fragment for each frame.
            BulkData bulkData = (BulkData) fragments.get(index+1);
            return new SegmentedImageInputStream(iis, bulkData.offset(), bulkData.length(), false);
        }
        /*
            At least one frame has multiple fragments. We try to figure out the offsets bounding this frame, then
            get the fragments within those offsets.
            If this is the only frame, the offsetTable may (and likely will) be empty -- in this case all fragments
            will be used.
            If there is more than one frame, we need to use the offsetTable to determine which fragments are part of
            this frame.
         */
        int startOffset = 0;
        int endOffset = -1;
        int offsetAdjust = 0;
        int n = fragments.size() - 1;
        BulkData offsetTable = (BulkData) fragments.get(0);

        if (offsetTable.length() > 0) {
            byte[] tableData = offsetTable.toBytes(null, offsetTable.bigEndian());
            startOffset = ByteUtils.bytesToInt(tableData, index * 4, offsetTable.bigEndian());
            if (index < frames - 1) {
                endOffset = ByteUtils.bytesToInt(tableData, (index + 1) * 4, offsetTable.bigEndian());
            }
            offsetAdjust = offsetTable.length();
        }

        List<Long> offsetList = new ArrayList<Long>();
        List<Integer> lengthList = new ArrayList<Integer>();
        for (int i = 0; i < n; i++, offsetAdjust -= 8) {
            /*
                The offset table is based on the raw bytes of the DICOM (7FE0,0010) pixel data, while the fragment
                offsets are based only on the concatenated contents of the (FFFE,E000) tags.
                The adjustment starts at the length of the offset table (as a zero offset here corresponds to the first
                byte following the table), and shrinks by 8 (FEFF00E0 + the length short) with each fragment.
             */
            BulkData bulkData = (BulkData) fragments.get(i+1);
            if (endOffset > 0 && bulkData.offset() >= (endOffset + offsetAdjust)) {
                break;
            }
            if (bulkData.offset() >= (startOffset + offsetAdjust)) {
                offsetList.add(bulkData.offset());
                lengthList.add(bulkData.length());
            }
        }

        long[] offsets = new long[offsetList.size()];
        int[] lengths = new int[lengthList.size()];
        for (int i = 0; i < offsetList.size(); i++) {
            offsets[i] = offsetList.get(i);
            lengths[i] = lengthList.get(i);
        }

        return new SegmentedImageInputStream(iis, offsets, lengths);
    }

    public long getLastSegmentEnd() {
        int i = segmentPositionsList.length - 1;
        return segmentPositionsList[i] + segmentLengths[i];
    }

    private int offsetOf(int segment) {
        int pos = 0;
        for (int i = 0; i < segment; ++i)
            pos += segmentLengths[i];
        return pos;
    }

    @Override
    public void seek(long pos) throws IOException {
        super.seek(pos);
        for (int i = 0, off = 0; i < segmentLengths.length; i++) {
            int end = off + segmentLengths[i];
            if (pos < end) {
                stream.seek(segmentPositionsList[i] + pos - off);
                curSegment = i;
                curSegmentEnd = end;
                return;
            }
            off = end;
        }
        curSegment = -1;
    }

    @Override
    public int read() throws IOException {
        if (!prepareRead())
            return -1;

        bitOffset = 0;
        int val = stream.read();
        if (val != -1) {
            ++streamPos;
        }
        return val;
    }

    private boolean prepareRead() throws IOException {
        if (curSegment < 0)
            return false;

        if (streamPos < curSegmentEnd)
            return true;

        if (curSegment+1 >= segmentPositionsList.length) {
            if (!autoExtend)
                return false;

            stream.mark();
            stream.readFully(header);
            stream.reset();
            if (ByteUtils.bytesToTagLE(header, 0) != Tag.Item)
                return false;

            addSegment(getLastSegmentEnd() + 8, ByteUtils.bytesToIntLE(header, 4));
        }

        seek(offsetOf(curSegment+1));
        return true;
    }

    private void addSegment(long pos, int len) {
        int i = segmentPositionsList.length;
        segmentPositionsList = Arrays.copyOf(segmentPositionsList, i + 1);
        segmentLengths = Arrays.copyOf(segmentLengths, i+1);
        segmentPositionsList[i] = pos;
        segmentLengths[i] = len;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!prepareRead())
            return -1;

        bitOffset = 0;
        int nbytes = stream.read(b, off,
                Math.min(len, (int) (curSegmentEnd-streamPos)));
        if (nbytes != -1) {
            streamPos += nbytes;
        }
        return nbytes;
    }
}
