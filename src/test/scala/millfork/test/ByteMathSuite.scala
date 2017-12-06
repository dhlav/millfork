package millfork.test

import millfork.test.emu.EmuBenchmarkRun
import org.scalatest.{FunSuite, Matchers}

/**
  * @author Karol Stasiak
  */
class ByteMathSuite extends FunSuite with Matchers {

  test("Complex expression") {
    EmuBenchmarkRun(
      """
        | byte output @$c000
        | void main () {
        |  output = (one() + one()) | (((one()<<2)-1) ^ one())
        | }
        | byte one() {
        |   return 1
        | }
      """.stripMargin)(_.readByte(0xc000) should equal(2))
  }

  test("Byte addition") {
    EmuBenchmarkRun(
      """
        | byte output @$c000
        | byte a
        | void main () {
        |  a = 1
        |  output = a + a
        | }
      """.stripMargin)(_.readByte(0xc000) should equal(2))
  }

  test("Byte addition 2") {
    EmuBenchmarkRun(
      """
        | byte output @$c000
        | byte a
        | void main () {
        |  a = 1
        |  output = a + 65
        | }
      """.stripMargin)(_.readByte(0xc000) should equal(66))
  }

  test("In-place byte addition") {
    EmuBenchmarkRun(
      """
        | array output[3] @$c000
        | byte a
        | void main () {
        |  a = 1
        |  output[1] = 5
        |  output[a] += 1
        |  output[a] += 36
        | }
      """.stripMargin)(_.readByte(0xc001) should equal(42))
  }

  test("Parameter order") {
    EmuBenchmarkRun(
      """
        | byte output @$c000
        | array arr[6]
        | void main () {
        |  output = 42
        | }
        | byte test1(byte a) @$6000 {
        |   return 5 + a
        | }
        | byte test2(byte a) @$6100 {
        |   return 5 | a
        | }
        | byte test3(byte a) @$6200 {
        |   return a + arr[a]
        | }
      """.stripMargin)(_.readByte(0xc000) should equal(42))
  }

  test("In-place byte addition 2") {
    EmuBenchmarkRun(
      """
        | array output[3] @$c000
        | void main () {
        |  byte x
        |  byte y
        |  byte tmpx
        |  byte tmpy
        |  tmpx = one()
        |  tmpy = one()
        |  x = tmpx
        |  y = tmpy
        |  output[y] = 36
        |  output[x] += 1
        | }
        | byte one() { return 1 }
      """.stripMargin)(_.readByte(0xc001) should equal(37))
  }

  test("In-place byte multiplication") {
    multiplyCase1(0, 0)
    multiplyCase1(0, 1)
    multiplyCase1(0, 2)
    multiplyCase1(0, 5)
    multiplyCase1(1, 0)
    multiplyCase1(5, 0)
    multiplyCase1(7, 0)
    multiplyCase1(2, 5)
    multiplyCase1(7, 2)
    multiplyCase1(100, 2)
    multiplyCase1(54, 4)
    multiplyCase1(2, 100)
    multiplyCase1(4, 54)
  }

  private def multiplyCase1(x: Int, y: Int): Unit = {
    EmuBenchmarkRun(
      s"""
         | byte output @$$c000
         | void main () {
         |  output = $x
         |  output *= $y
         | }
          """.
        stripMargin)(_.readByte(0xc000) should equal(x * y))
  }

  test("Byte multiplication") {
    multiplyCase2(0, 0)
    multiplyCase2(0, 1)
    multiplyCase2(0, 2)
    multiplyCase2(0, 5)
    multiplyCase2(1, 0)
    multiplyCase2(5, 0)
    multiplyCase2(7, 0)
    multiplyCase2(2, 5)
    multiplyCase2(7, 2)
    multiplyCase2(100, 2)
    multiplyCase2(54, 4)
    multiplyCase2(2, 100)
    multiplyCase2(4, 54)
  }

  private def multiplyCase2(x: Int, y: Int): Unit = {
    EmuBenchmarkRun(
      s"""
         | byte output @$$c000
         | void main () {
         |  byte a
         |  a = $x
         |  output = a * $y
         | }
          """.
        stripMargin)(_.readByte(0xc000) should equal(x * y))
  }
}
