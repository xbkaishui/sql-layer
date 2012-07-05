/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.ais.model;

import com.akiban.ais.model.validation.AISInvariants;
import com.akiban.server.rowdata.FieldDef;

public class Column
{
    public static Column create(Table table, String name, Integer position, Type type, Boolean nullable,
                                Long typeParameter1, Long typeParameter2, Long initialAutoIncValue,
                                CharsetAndCollation charsetAndCollation)
    {
        table.checkMutability();
        AISInvariants.checkNullName(name, "column", "column name");
        AISInvariants.checkDuplicateColumnsInTable(table, name);
        AISInvariants.checkDuplicateColumnPositions(table, position);
        Column column = new Column(table, name, position, type, nullable, typeParameter1, typeParameter2,
                                   initialAutoIncValue, charsetAndCollation);
        table.addColumn(column);
        return column;
    }

    public static Column create(Table table, String name, Integer position, Type type) {
        return create(table, name, position, type, null, null, null, null, null);
    }

    @Override
    public String toString()
    {
        return table.getName().getTableName() + "." + columnName;
        /*** Too verbose for my taste. Restore this if you really need it
        StringBuilder typeDescription = new StringBuilder();
        typeDescription.append(type.name());
        if (type.nTypeParameters() > 0) {
            typeDescription.append("(");
            typeDescription.append(typeParameter1);
            if (type.nTypeParameters() > 1) {
                typeDescription.append(", ");
                typeDescription.append(typeParameter2);
            }
            typeDescription.append(")");
        }
        String s;
        if (groupColumn == null && userColumn == null) {
            s = "Column(" + columnName + ": " + typeDescription + ")";
        } else if (groupColumn != null) {
            s = "Column(" + columnName + ": " + typeDescription + " -> "
                + groupColumn.getTable().getName().getTableName() + "."
                + groupColumn.getName() + ")";
        } else {
            s = "Column(" + columnName + ": " + typeDescription + " -> "
                + userColumn.getTable().getName().getTableName() + "."
                + userColumn.getName() + ")";
        }
        return s;
         ***/
    }

    public void setNullable(Boolean nullable)
    {
        this.nullable = nullable;
    }
    
    public void setAutoIncrement(Boolean autoIncrement)
    {
        this.initialAutoIncrementValue = autoIncrement ? 1L /* mysql default */ : null;
    }
    
    public void setTypeParameter1(Long typeParameter1)
    {
        if(typeParameter1 != null) {
            this.typeParameter1 = typeParameter1;
        }
    }

    public void setTypeParameter2(Long typeParameter2)
    {
        if (typeParameter2 != null) {
            this.typeParameter2 = typeParameter2;
        }
    }

    public void setCharsetAndCollation(CharsetAndCollation charsetAndCollation)
    {
        this.charsetAndCollation = charsetAndCollation;
    }

    public void setCharset(String charset)
    {
        if (charset != null) {
            this.charsetAndCollation = CharsetAndCollation.intern(charset, getCharsetAndCollation().collation());
        }
    }

    public void setCollation(String collation)
    {
        if (collation != null) {
            this.charsetAndCollation = CharsetAndCollation.intern(getCharsetAndCollation().charset(), collation);
        }
    }

    public void setGroupColumn(Column column)
    {
        assert column == null || groupColumn == null : groupColumn;
        groupColumn = column;
    }

    public void setUserColumn(Column column)
    {
        assert userColumn == null
                : "this may happen because you have two tables with the same column name, but different schemas: "
                + userColumn;
        userColumn = column;
    }

    public String getDescription()
    {
        StringBuilder buffer = new StringBuilder();
        buffer.append(table.getName().getSchemaName());
        buffer.append(".");
        buffer.append(table.getName().getTableName());
        buffer.append(".");
        buffer.append(getName());
        return buffer.toString();
    }

    public String getTypeDescription()
    {
        StringBuilder columnType = new StringBuilder();
        columnType.append(type.name());
        switch (type.nTypeParameters()) {
            case 0:
                break;
            case 1:
                String str1 = "(" + typeParameter1 + ")";
                columnType.append(str1);
                break;
            case 2:
                String str2 = "(" + typeParameter1 + ", " + typeParameter2 + ")";
                columnType.append(str2);
                break;
        }
        return columnType.toString();
    }

    public String getName()
    {
        return columnName;
    }

    public Integer getPosition()
    {
        return position;
    }

    public Type getType()
    {
        return type;
    }

    public Boolean getNullable()
    {
        return nullable;
    }

    public Long getTypeParameter1()
    {
        return typeParameter1;
    }

    public Long getTypeParameter2()
    {
        return typeParameter2;
    }

    public Column getGroupColumn()
    {
        return groupColumn;
    }

    public Column getUserColumn()
    {
        return userColumn;
    }

    public Table getTable()
    {
        return table;
    }

    public UserTable getUserTable()
    {
        return (UserTable) getTable();
    }

    public Long getInitialAutoIncrementValue()
    {
        return initialAutoIncrementValue;
    }

    public Long getMaxStorageSize()
    {
        long maxStorageSize;
        if (type.equals(Types.VARCHAR) || type.equals(Types.CHAR)) {
            long maxCharacters = paramCheck(typeParameter1);
            final long charWidthMultiplier = maxCharacterWidth();
            long maxBytes = maxCharacters * charWidthMultiplier;
            maxStorageSize = maxBytes + prefixSize(maxBytes);
        } else if (type.equals(Types.VARBINARY)) {
            long maxBytes = paramCheck(typeParameter1);
            maxStorageSize = maxBytes + prefixSize(maxBytes);
        } else if (type.equals(Types.ENUM)) {
        	maxStorageSize = paramCheck(typeParameter1) < 256 ? 1 : 2;
        } else if (type.equals(Types.SET)) {
        	long members = paramCheck(typeParameter1);
            maxStorageSize =
                members <= 8 ? 1 :
        	    members <= 16 ? 2 :
        	    members <= 24 ? 3 :
        	    members <= 32 ? 4 : 8;
        } else if (type.equals(Types.DECIMAL) || type.equals(Types.U_DECIMAL)) {
            final int TYPE_SIZE = 4;
            final int DIGIT_PER = 9;
            final int BYTE_DIGITS[] = { 0, 1, 1, 2, 2, 3, 3, 4, 4, 4 };

            final int precision = getTypeParameter1().intValue();
            final int scale = getTypeParameter2().intValue();

            final int intCount = precision - scale;
            final int intFull = intCount / DIGIT_PER;
            final int intPart = intCount % DIGIT_PER;
            final int fracFull = scale / DIGIT_PER;
            final int fracPart = scale % DIGIT_PER;

            return (long) (intFull + fracFull) * TYPE_SIZE + BYTE_DIGITS[intPart] + BYTE_DIGITS[fracPart];
        } else if (!type.fixedSize()) {
            long maxBytes = type.maxSizeBytes();
            maxStorageSize = Math.min(Types.MAX_STORAGE_SIZE_CAP, maxBytes) 
                + prefixSize(maxBytes);
        } else {
            maxStorageSize = type.maxSizeBytes();
        }
        return maxStorageSize;
    }

    /** Same, but assume that characters take a common number of
     * bytes, not the encoding's max.
     */
    public long getAverageStorageSize()
    {
        if (type.equals(Types.VARCHAR) || type.equals(Types.CHAR)) {
            long maxCharacters = paramCheck(typeParameter1);
            final long charWidthMultiplier = averageCharacterWidth();
            long maxBytes = maxCharacters * charWidthMultiplier;
            return maxBytes + prefixSize(maxBytes);
        }
        else {
            return getMaxStorageSize();
        }
    }

    public Integer getPrefixSize()
    {
        int prefixSize;
        if (type.equals(Types.VARCHAR) || type.equals(Types.CHAR)) {
            final long maxCharacters = paramCheck(typeParameter1);
            final long charWidthMultiplier = maxCharacterWidth();
            final long maxBytes = maxCharacters * charWidthMultiplier;
            prefixSize = prefixSize(maxBytes);
        } else if (type.equals(Types.VARBINARY)) {
            prefixSize = prefixSize(paramCheck(typeParameter1));
        } else if (!type.fixedSize()) {
            prefixSize = prefixSize(type.maxSizeBytes());
        } else {
            prefixSize = 0;
        }
        return prefixSize;
    }

    public Boolean isAkibanPKColumn()
    {
        return columnName.equals(AKIBAN_PK_NAME);
    }

    /**
     * Compute the maximum character width.  This is used to determine how many bytes
     * will be reserved to encode the length in bytes of a VARCHAR or other text field.
     * TODO: We need to implement a character set table to embody knowledge of many
     * different character sets.  This is simply a stub to get us past the UTF8
     * problem.
     * @return
     */
    private int maxCharacterWidth() {
        // See bug687205
        if (charsetAndCollation != null && "utf8".equalsIgnoreCase(charsetAndCollation.charset())) {
            return 3;
        } else {
            return 1;
        }
    }

    private int averageCharacterWidth() {
        return 1;
    }

    /**
     * @return This column's CharsetAndCollation if it has one, otherwise the owning Table's
     */
    public CharsetAndCollation getCharsetAndCollation()
    {
        return
            charsetAndCollation == null
            ? table.getCharsetAndCollation()
            : charsetAndCollation;
    }

    // Note: made public for AISBuilder -- peter.  TODO remove this comment.
    public void setInitialAutoIncrementValue(Long initialAutoIncrementValue)
    {
        this.initialAutoIncrementValue = initialAutoIncrementValue;
    }

    public void setFieldDef(FieldDef fieldDef)
    {
        this.fieldDef = fieldDef;
    }

    public FieldDef getFieldDef()
    {
        return fieldDef;
    }

    private Column(Table table,
                   String columnName,
                   Integer position,
                   Type type,
                   Boolean nullable,
                   Long typeParameter1,
                   Long typeParameter2,
                   Long initialAutoIncValue,
                   CharsetAndCollation charsetAndCollation)
    {
        this.table = table;
        this.columnName = columnName;
        this.position = position;
        this.type = type;
        this.nullable = nullable;
        this.typeParameter1 = typeParameter1;
        this.typeParameter2 = typeParameter2;
        this.initialAutoIncrementValue = initialAutoIncValue;
        this.charsetAndCollation = charsetAndCollation;

        Long[] defaults = Types.defaultParams().get(type);
        if(defaults != null) {
            if(this.typeParameter1 == null) {
                this.typeParameter1 = defaults[0];
            }
            if(this.typeParameter2 == null) {
                this.typeParameter2 = defaults[1];
            }
        }
    }

    private long paramCheck(final Number param)
    {
        if (param == null || param.longValue() < 0) {
            throw new IllegalStateException(this + " needs a positive column width parameter");
        }
        return param.longValue();
    }

    private int prefixSize(final long byteCount)
    {
        return
            byteCount < 256 ? 1 :
            byteCount < 65536 ? 2 :
            byteCount < 16777216 ? 3 : 4;
    }

    // State

    public static final String AKIBAN_PK_NAME = "__akiban_pk";

    private final String columnName;
    private final Type type;
    private final Table table;
    private Boolean nullable;
    private Integer position;
    private Long typeParameter1;
    private Long typeParameter2;
    private Long initialAutoIncrementValue;
    private CharsetAndCollation charsetAndCollation;

    private Column groupColumn; // Non-null iff this is a user table column
    private Column userColumn; // Non-null iff this is a group table column
    private FieldDef fieldDef;
}
