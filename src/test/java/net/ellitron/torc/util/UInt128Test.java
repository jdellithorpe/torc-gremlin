/* Copyright (c) 2015-2019 Stanford University
 *
 * Permission to use, copy, modify, and distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR(S) DISCLAIM ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL AUTHORS BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package net.ellitron.torc.util;

import static org.junit.Assert.*;

import org.junit.Test;

import java.math.BigInteger;

/**
 *
 * @author Jonathan Ellithorpe (jde@cs.stanford.edu)
 */
public class UInt128Test {

  public UInt128Test() {
  }

  @Test
  public void constructor_fromByte() {
    UInt128 dut = new UInt128((byte) -1);
    assertEquals("0xFF", dut.toString());
    dut = new UInt128((byte) 0xDE);
    assertEquals("0xDE", dut.toString());
  }

  @Test
  public void constructor_fromShort() {
    UInt128 dut = new UInt128((short) -1);
    assertEquals("0xFFFF", dut.toString());
    dut = new UInt128((short) 0xDEAD);
    assertEquals("0xDEAD", dut.toString());
  }

  @Test
  public void constructor_fromInteger() {
    UInt128 dut = new UInt128((int) -1);
    assertEquals("0xFFFFFFFF", dut.toString());
    dut = new UInt128((int) 0xDEADBEEF);
    assertEquals("0xDEADBEEF", dut.toString());
  }

  @Test
  public void constructor_fromLong() {
    UInt128 dut = new UInt128((long) -1);
    assertEquals("0xFFFFFFFFFFFFFFFF", dut.toString());
    dut = new UInt128((long) 0xDEADBEEFDEADBEEFL);
    assertEquals("0xDEADBEEFDEADBEEF", dut.toString());
  }

  @Test
  public void constructor_fromString() {
    UInt128 dut = new UInt128("1");
    assertEquals("0x1", dut.toString());
  }

  @Test
  public void constructor_fromStringWithBase() {
    UInt128 dut = new UInt128("DEADBEEF", 16);
    assertEquals("0xDEADBEEF", dut.toString());
  }

  @Test
  public void constructor_fromBigInteger() {
    UInt128 dut = new UInt128(new BigInteger("-1"));
    assertEquals("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", dut.toString());
    dut = new UInt128(new BigInteger("-2"));
    assertEquals("0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFE", dut.toString());
    dut = new UInt128(new BigInteger("1", 16));
    assertEquals("0x1", dut.toString());
    dut = new UInt128(new BigInteger("2", 16));
    assertEquals("0x2", dut.toString());
    dut = new UInt128(new BigInteger("A", 16));
    assertEquals("0xA", dut.toString());
    dut = new UInt128(new BigInteger("DEADBEEF", 16));
    assertEquals("0xDEADBEEF", dut.toString());
    dut = new UInt128(new BigInteger("DEADBEEFDEADBEEF", 16));
    assertEquals("0xDEADBEEFDEADBEEF", dut.toString());
    dut = new UInt128(new BigInteger("DEADBEEFDEADBEEFDEADBEEF", 16));
    assertEquals("0xDEADBEEFDEADBEEFDEADBEEF", dut.toString());
    dut = new UInt128(new BigInteger("DEADBEEFDEADBEEFDEADBEEFDEADBEEF", 16));
    assertEquals("0xDEADBEEFDEADBEEFDEADBEEFDEADBEEF", dut.toString());
    dut = new UInt128(BigInteger.ONE.shiftLeft(63));
    assertEquals("0x8000000000000000", dut.toString());
    dut = new UInt128(BigInteger.ONE.shiftLeft(64));
    assertEquals("0x10000000000000000", dut.toString());
    dut = new UInt128(BigInteger.ONE.shiftLeft(127));
    assertEquals("0x80000000000000000000000000000000", dut.toString());
    dut = new UInt128(BigInteger.ONE.shiftLeft(128));
    assertEquals("0x0", dut.toString());
  }

  @Test
  public void constructor_fromByteArray() {
    UInt128 dut = new UInt128(new byte[]{(byte) 0xDE});
    assertEquals("0xDE", dut.toString());
    dut = new UInt128(new byte[]{(byte) 0xDE, (byte) 0xAD});
    assertEquals("0xDEAD", dut.toString());
    dut =
        new UInt128(new byte[]{
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
    assertEquals("0xDEADBEEF", dut.toString());
    dut =
        new UInt128(new byte[]{
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
    assertEquals("0xDEADBEEFDEADBEEF", dut.toString());
    dut =
        new UInt128(new byte[]{
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
    assertEquals("0xDEADBEEFDEADBEEFDEADBEEFDEADBEEF", dut.toString());
    dut =
        new UInt128(new byte[]{(byte) 0xFF, 
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
          (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF});
    assertEquals("0xDEADBEEFDEADBEEFDEADBEEFDEADBEEF", dut.toString());
  }

  @Test
  public void constructor_fromTwoLongs() {
    UInt128 dut = new UInt128(1, 1);
    assertEquals("0x10000000000000001", dut.toString());
    dut = new UInt128((1L << 63) + 5, 3);
    assertEquals("0x80000000000000050000000000000003", dut.toString());
  }

  @Test
  public void toByteArray_outputFedToConstructor_sameValue() {
    UInt128 src = new UInt128(0, 1);
    UInt128 cpy = new UInt128(src.toByteArray());
    assertEquals(src.toString(), cpy.toString());
    src = new UInt128((1L << 63) + 5, 3);
    cpy = new UInt128(src.toByteArray());
    assertEquals(src.toString(), cpy.toString());
    src = new UInt128("-1");
    cpy = new UInt128(src.toByteArray());
    assertEquals(src.toString(), cpy.toString());
    src = new UInt128("DEADBEEF", 16);
    cpy = new UInt128(src.toByteArray());
    assertEquals(src.toString(), cpy.toString());
  }

  @Test
  public void compareTo_variousComparisons() {
    UInt128 a = new UInt128("80000000000000000000000000000000", 16);
    UInt128 b = new UInt128("40000000000000000000000000000000", 16);
    assertTrue(a.compareTo(b) > 0);
    assertTrue(b.compareTo(a) < 0);
    a = new UInt128("80000000000000010000000000000000", 16);
    b = new UInt128("80000000000000000000000000000000", 16);
    assertTrue(a.compareTo(b) > 0);
    assertTrue(b.compareTo(a) < 0);
    a = new UInt128("80000000000000008000000000000000", 16);
    b = new UInt128("80000000000000004000000000000000", 16);
    assertTrue(a.compareTo(b) > 0);
    assertTrue(b.compareTo(a) < 0);
    a = new UInt128("80000000000000008000000000000001", 16);
    b = new UInt128("80000000000000008000000000000000", 16);
    assertTrue(a.compareTo(b) > 0);
    assertTrue(b.compareTo(a) < 0);
    a = new UInt128("00000000000000001000000000000000", 16);
    b = new UInt128("00000000000000000000000000000000", 16);
    assertTrue(a.compareTo(b) > 0);
    assertTrue(b.compareTo(a) < 0);
    a = new UInt128("00000000000000000000000000000001", 16);
    b = new UInt128("00000000000000000000000000000000", 16);
    assertTrue(a.compareTo(b) > 0);
    assertTrue(b.compareTo(a) < 0);
    a = new UInt128("DEADBEEFDEADBEEFDEADBEEFDEADBEEF", 16);
    b = new UInt128("DEADBEEFDEADBEEFDEADBEEFDEADBEEF", 16);
    assertTrue(a.compareTo(b) == 0);
    assertTrue(b.compareTo(a) == 0);
  }

  @Test
  public void equals_sameValue() {
    UInt128 a = new UInt128("DEADBEEFDEADBEEFDEADBEEFDEADBEEF", 16);
    UInt128 b = new UInt128("DEADBEEFDEADBEEFDEADBEEFDEADBEEF", 16);
    assertTrue(a.compareTo(b) == 0);
    assertTrue(b.compareTo(a) == 0);
  }
}
