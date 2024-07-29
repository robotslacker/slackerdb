package org.slackerdb.protocol;

public class Field {
    public byte[]  name;
    public int     objectIdOfTable = 0;
    public short   attributeNumberOfColumn = 0;
    public int     dataTypeId = 0;
    public short   dataTypeSize;
    public int     dataTypeModifier;
    public short   formatCode;

    public int getFieldSize()
    {
        // name.length + terminator + int + short + int + short + int + short
        return name.length + 1 + 4 + 2 + 4 + 2 + 4 + 2;
    }
}
