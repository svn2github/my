/*
 * DynamicJava - Copyright (C) 1999 Dyade
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions: The above copyright notice and this
 * permission notice shall be included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL DYADE BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
 * THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * 
 * Except as contained in this notice, the name of Dyade shall not be used in advertising or
 * otherwise to promote the sale, use or other dealings in this Software without prior written
 * authorization from Dyade.
 */

/* Generated By:JavaCC: Do not edit this line. ASCII_UCodeESC_CharStream.java Version 0.7pre6 */
package koala.dynamicjava.parser;

/**
 * An implementation of interface CharStream, where the stream is assumed to contain only ASCII
 * characters (with java-like unicode escape processing).
 */

public final class ASCII_UCodeESC_CharStream {
    public static final boolean staticFlag = false;

    static final int hexval(final char c) throws java.io.IOException {
        switch (c) {
        case '0':
            return 0;
        case '1':
            return 1;
        case '2':
            return 2;
        case '3':
            return 3;
        case '4':
            return 4;
        case '5':
            return 5;
        case '6':
            return 6;
        case '7':
            return 7;
        case '8':
            return 8;
        case '9':
            return 9;

        case 'a':
        case 'A':
            return 10;
        case 'b':
        case 'B':
            return 11;
        case 'c':
        case 'C':
            return 12;
        case 'd':
        case 'D':
            return 13;
        case 'e':
        case 'E':
            return 14;
        case 'f':
        case 'F':
            return 15;
        }

        throw new java.io.IOException(); // Should never come here
    }

    public int bufpos = -1;
    int bufsize;
    int available;
    int tokenBegin;
    private int bufline[];
    private int bufcolumn[];

    private int column = 0;
    private int line = 1;

    private java.io.Reader inputStream;

    private boolean prevCharIsCR = false;
    private boolean prevCharIsLF = false;

    private char[] nextCharBuf;
    private char[] buffer;
    private int maxNextCharInd = 0;
    private int nextCharInd = -1;
    private int inBuf = 0;

    private final void ExpandBuff(final boolean wrapAround) {
        final char[] newbuffer = new char[this.bufsize + 2048];
        final int newbufline[] = new int[this.bufsize + 2048];
        final int newbufcolumn[] = new int[this.bufsize + 2048];

        try {
            if (wrapAround) {
                System.arraycopy(this.buffer, this.tokenBegin, newbuffer, 0, this.bufsize - this.tokenBegin);
                System.arraycopy(this.buffer, 0, newbuffer, this.bufsize - this.tokenBegin, this.bufpos);
                this.buffer = newbuffer;

                System.arraycopy(this.bufline, this.tokenBegin, newbufline, 0, this.bufsize - this.tokenBegin);
                System.arraycopy(this.bufline, 0, newbufline, this.bufsize - this.tokenBegin, this.bufpos);
                this.bufline = newbufline;

                System.arraycopy(this.bufcolumn, this.tokenBegin, newbufcolumn, 0, this.bufsize - this.tokenBegin);
                System.arraycopy(this.bufcolumn, 0, newbufcolumn, this.bufsize - this.tokenBegin, this.bufpos);
                this.bufcolumn = newbufcolumn;

                this.bufpos += this.bufsize - this.tokenBegin;
            } else {
                System.arraycopy(this.buffer, this.tokenBegin, newbuffer, 0, this.bufsize - this.tokenBegin);
                this.buffer = newbuffer;

                System.arraycopy(this.bufline, this.tokenBegin, newbufline, 0, this.bufsize - this.tokenBegin);
                this.bufline = newbufline;

                System.arraycopy(this.bufcolumn, this.tokenBegin, newbufcolumn, 0, this.bufsize - this.tokenBegin);
                this.bufcolumn = newbufcolumn;

                this.bufpos -= this.tokenBegin;
            }
        } catch (final Throwable t) {
            throw new Error(t.getMessage());
        }

        this.available = this.bufsize += 2048;
        this.tokenBegin = 0;
    }

    private final void FillBuff() throws java.io.IOException {
        int i;
        if (this.maxNextCharInd == 4096) {
            this.maxNextCharInd = this.nextCharInd = 0;
        }

        try {
            if ((i = this.inputStream.read(this.nextCharBuf, this.maxNextCharInd, 4096 - this.maxNextCharInd)) == -1) {
                this.inputStream.close();
                throw new java.io.IOException();
            } else {
                this.maxNextCharInd += i;
            }
            return;
        } catch (final java.io.IOException e) {
            if (this.bufpos != 0) {
                --this.bufpos;
                backup(0);
            } else {
                this.bufline[this.bufpos] = this.line;
                this.bufcolumn[this.bufpos] = this.column;
            }
            throw e;
        }
    }

    private final char ReadByte() throws java.io.IOException {
        if (++this.nextCharInd >= this.maxNextCharInd) {
            FillBuff();
        }

        return this.nextCharBuf[this.nextCharInd];
    }

    public final char BeginToken() throws java.io.IOException {
        if (this.inBuf > 0) {
            --this.inBuf;
            return this.buffer[this.tokenBegin = this.bufpos == this.bufsize - 1 ? (this.bufpos = 0) : ++this.bufpos];
        }

        this.tokenBegin = 0;
        this.bufpos = -1;

        return readChar();
    }

    private final void AdjustBuffSize() {
        if (this.available == this.bufsize) {
            if (this.tokenBegin > 2048) {
                this.bufpos = 0;
                this.available = this.tokenBegin;
            } else {
                ExpandBuff(false);
            }
        } else if (this.available > this.tokenBegin) {
            this.available = this.bufsize;
        } else if (this.tokenBegin - this.available < 2048) {
            ExpandBuff(true);
        } else {
            this.available = this.tokenBegin;
        }
    }

    private final void UpdateLineColumn(final char c) {
        this.column++;

        if (this.prevCharIsLF) {
            this.prevCharIsLF = false;
            this.line += this.column = 1;
        } else if (this.prevCharIsCR) {
            this.prevCharIsCR = false;
            if (c == '\n') {
                this.prevCharIsLF = true;
            } else {
                this.line += this.column = 1;
            }
        }

        switch (c) {
        case '\r':
            this.prevCharIsCR = true;
            break;
        case '\n':
            this.prevCharIsLF = true;
            break;
        case '\t':
            this.column--;
            this.column += 8 - (this.column & 07);
            break;
        default:
            break;
        }

        this.bufline[this.bufpos] = this.line;
        this.bufcolumn[this.bufpos] = this.column;
    }

    public final char readChar() throws java.io.IOException {
        if (this.inBuf > 0) {
            --this.inBuf;
            return this.buffer[this.bufpos == this.bufsize - 1 ? (this.bufpos = 0) : ++this.bufpos];
        }

        char c;

        if (++this.bufpos == this.available) {
            AdjustBuffSize();
        }

        if ((this.buffer[this.bufpos] = c = (char) ((char) 0xff & ReadByte())) == '\\') {
            UpdateLineColumn(c);

            int backSlashCnt = 1;

            for (;;) // Read all the backslashes
            {
                if (++this.bufpos == this.available) {
                    AdjustBuffSize();
                }

                try {
                    if ((this.buffer[this.bufpos] = c = (char) ((char) 0xff & ReadByte())) != '\\') {
                        UpdateLineColumn(c);
                        // found a non-backslash char.
                        if (c == 'u' && (backSlashCnt & 1) == 1) {
                            if (--this.bufpos < 0) {
                                this.bufpos = this.bufsize - 1;
                            }

                            break;
                        }

                        backup(backSlashCnt);
                        return '\\';
                    }
                } catch (final java.io.IOException e) {
                    if (backSlashCnt > 1) {
                        backup(backSlashCnt);
                    }

                    return '\\';
                }

                UpdateLineColumn(c);
                backSlashCnt++;
            }

            // Here, we have seen an odd number of backslash's followed by a 'u'
            try {
                while ((c = (char) ((char) 0xff & ReadByte())) == 'u') {
                    ++this.column;
                }

                this.buffer[this.bufpos] = c = (char) (hexval(c) << 12 | hexval((char) ((char) 0xff & ReadByte())) << 8 | hexval((char) ((char) 0xff & ReadByte())) << 4 | hexval((char) ((char) 0xff & ReadByte())));

                this.column += 4;
            } catch (final java.io.IOException e) {
                throw new Error("Invalid escape character at line " + this.line + " column " + this.column + ".");
            }

            if (backSlashCnt == 1) {
                return c;
            } else {
                backup(backSlashCnt - 1);
                return '\\';
            }
        } else {
            UpdateLineColumn(c);
            return c;
        }
    }

    /**
     * @deprecated
     * @see #getEndColumn
     */

    @Deprecated
    public final int getColumn() {
        return this.bufcolumn[this.bufpos];
    }

    /**
     * @deprecated
     * @see #getEndLine
     */

    @Deprecated
    public final int getLine() {
        return this.bufline[this.bufpos];
    }

    public final int getEndColumn() {
        return this.bufcolumn[this.bufpos];
    }

    public final int getEndLine() {
        return this.bufline[this.bufpos];
    }

    public final int getBeginColumn() {
        return this.bufcolumn[this.tokenBegin];
    }

    public final int getBeginLine() {
        return this.bufline[this.tokenBegin];
    }

    public final void backup(final int amount) {

        this.inBuf += amount;
        if ((this.bufpos -= amount) < 0) {
            this.bufpos += this.bufsize;
        }
    }

    public ASCII_UCodeESC_CharStream(final java.io.Reader dstream, final int startline, final int startcolumn, final int buffersize) {
        this.inputStream = dstream;
        this.line = startline;
        this.column = startcolumn - 1;

        this.available = this.bufsize = buffersize;
        this.buffer = new char[buffersize];
        this.bufline = new int[buffersize];
        this.bufcolumn = new int[buffersize];
        this.nextCharBuf = new char[4096];
    }

    public ASCII_UCodeESC_CharStream(final java.io.Reader dstream, final int startline, final int startcolumn) {
        this(dstream, startline, startcolumn, 4096);
    }

    public void ReInit(final java.io.Reader dstream, final int startline, final int startcolumn, final int buffersize) {
        this.inputStream = dstream;
        this.line = startline;
        this.column = startcolumn - 1;

        if (this.buffer == null || buffersize != this.buffer.length) {
            this.available = this.bufsize = buffersize;
            this.buffer = new char[buffersize];
            this.bufline = new int[buffersize];
            this.bufcolumn = new int[buffersize];
            this.nextCharBuf = new char[4096];
        }
        this.prevCharIsLF = this.prevCharIsCR = false;
        this.tokenBegin = this.inBuf = this.maxNextCharInd = 0;
        this.nextCharInd = this.bufpos = -1;
    }

    public void ReInit(final java.io.Reader dstream, final int startline, final int startcolumn) {
        ReInit(dstream, startline, startcolumn, 4096);
    }

    public ASCII_UCodeESC_CharStream(final java.io.InputStream dstream, final int startline, final int startcolumn, final int buffersize) {
        this(new java.io.InputStreamReader(dstream), startline, startcolumn, 4096);
    }

    public ASCII_UCodeESC_CharStream(final java.io.InputStream dstream, final int startline, final int startcolumn) {
        this(dstream, startline, startcolumn, 4096);
    }

    public void ReInit(final java.io.InputStream dstream, final int startline, final int startcolumn, final int buffersize) {
        ReInit(new java.io.InputStreamReader(dstream), startline, startcolumn, 4096);
    }

    public void ReInit(final java.io.InputStream dstream, final int startline, final int startcolumn) {
        ReInit(dstream, startline, startcolumn, 4096);
    }

    public final String GetImage() {
        if (this.bufpos >= this.tokenBegin) {
            return new String(this.buffer, this.tokenBegin, this.bufpos - this.tokenBegin + 1);
        } else {
            return new String(this.buffer, this.tokenBegin, this.bufsize - this.tokenBegin) + new String(this.buffer, 0, this.bufpos + 1);
        }
    }

    public final char[] GetSuffix(final int len) {
        final char[] ret = new char[len];

        if (this.bufpos + 1 >= len) {
            System.arraycopy(this.buffer, this.bufpos - len + 1, ret, 0, len);
        } else {
            System.arraycopy(this.buffer, this.bufsize - (len - this.bufpos - 1), ret, 0, len - this.bufpos - 1);
            System.arraycopy(this.buffer, 0, ret, len - this.bufpos - 1, this.bufpos + 1);
        }

        return ret;
    }

    public void Done() {
        this.nextCharBuf = null;
        this.buffer = null;
        this.bufline = null;
        this.bufcolumn = null;
    }

    /**
     * Method to adjust line and column numbers for the start of a token.<BR>
     */
    public void adjustBeginLineColumn(int newLine, final int newCol) {
        int start = this.tokenBegin;
        int len;

        if (this.bufpos >= this.tokenBegin) {
            len = this.bufpos - this.tokenBegin + this.inBuf + 1;
        } else {
            len = this.bufsize - this.tokenBegin + this.bufpos + 1 + this.inBuf;
        }

        int i = 0, j = 0, k = 0;
        int nextColDiff = 0, columnDiff = 0;

        while (i < len && this.bufline[j = start % this.bufsize] == this.bufline[k = ++start % this.bufsize]) {
            this.bufline[j] = newLine;
            nextColDiff = columnDiff + this.bufcolumn[k] - this.bufcolumn[j];
            this.bufcolumn[j] = newCol + columnDiff;
            columnDiff = nextColDiff;
            i++;
        }

        if (i < len) {
            this.bufline[j] = newLine++;
            this.bufcolumn[j] = newCol + columnDiff;

            while (i++ < len) {
                if (this.bufline[j = start % this.bufsize] != this.bufline[++start % this.bufsize]) {
                    this.bufline[j] = newLine++;
                } else {
                    this.bufline[j] = newLine;
                }
            }
        }

        this.line = this.bufline[j];
        this.column = this.bufcolumn[j];
    }

}
