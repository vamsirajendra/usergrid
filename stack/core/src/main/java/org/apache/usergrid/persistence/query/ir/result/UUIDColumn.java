package org.apache.usergrid.persistence.query.ir.result;


import java.nio.ByteBuffer;
import java.util.UUID;

import org.apache.usergrid.persistence.cassandra.Serializers;
import org.apache.usergrid.utils.UUIDUtils;


/**
 * Used as a comparator for columns
 */
class UUIDColumn extends AbstractScanColumn{

    private final int compareReversed;

    protected UUIDColumn( final UUID uuid, final ByteBuffer columnNameBuffer, final int compareReversed  ) {
        super( uuid, columnNameBuffer );
        this.compareReversed = compareReversed;
    }


    public UUIDColumn( final UUID uuid, final int compareReversed ) {
        super(uuid, Serializers.ue.toByteBuffer( uuid ));
        this.compareReversed = compareReversed;
    }




    @Override
    public int compareTo( final ScanColumn other ) {
        return  UUIDUtils.compare( uuid, other.getUUID() ) * compareReversed;
    }


}