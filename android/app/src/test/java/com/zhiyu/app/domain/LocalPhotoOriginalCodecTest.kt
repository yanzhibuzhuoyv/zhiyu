package com.zhiyu.app.domain

import org.junit.Assert.*
import org.junit.Test

class LocalPhotoOriginalCodecTest {
    @Test fun pictureOnlyOriginalRetainsExactlyZeroTextBytes() {
        val encoded=LocalMemoryCodec.encode("")
        assertEquals(0,encoded.utf8Bytes)
        assertEquals(LocalMemoryCodec.sha(byteArrayOf()),encoded.sha256)
        assertEquals("",LocalMemoryCodec.decode(encoded.compressed,0,encoded.sha256))
        assertEquals("",LocalMemoryCodec.decodeTail(encoded.compressed,0,encoded.sha256,128).text)
        assertThrows(IllegalArgumentException::class.java) { LocalMemoryCodec.decode(encoded.compressed,0,"a".repeat(64)) }
    }
}
