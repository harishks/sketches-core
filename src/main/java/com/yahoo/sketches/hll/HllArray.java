/*
 * Copyright 2017, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.sketches.hll;

import static com.yahoo.sketches.Util.invPow2;
import static com.yahoo.sketches.hll.HllUtil.MIN_LOG_K;
import static com.yahoo.sketches.hll.PreambleUtil.FAMILY_ID;
import static com.yahoo.sketches.hll.PreambleUtil.HLL_BYTE_ARRAY_START;
import static com.yahoo.sketches.hll.PreambleUtil.HLL_PREINTS;
import static com.yahoo.sketches.hll.PreambleUtil.SER_VER;
import static com.yahoo.sketches.hll.PreambleUtil.extractFamilyId;
import static com.yahoo.sketches.hll.PreambleUtil.extractPreInts;
import static com.yahoo.sketches.hll.PreambleUtil.extractSerVer;
import static com.yahoo.sketches.hll.TgtHllType.HLL_4;
import static com.yahoo.sketches.hll.TgtHllType.HLL_6;

import com.yahoo.memory.Memory;

/**
 * @author Lee Rhodes
 * @author Kevin Lang
 */
abstract class HllArray extends HllSketchImpl {
  private static final double HLL_HIP_RSE_FACTOR = 0.8360;
  private static final double HLL_NON_HIP_RSE_FACTOR = 1.0464;
  int curMin; //only changed by Hll4Array
  int numAtCurMin;
  double hipAccum;
  double kxq0;
  double kxq1;
  byte[] hllByteArr = null; //init by sub-classes
  AuxHashMap auxHashMap = null; //used only by Hll4Array

  /**
   * Standard constructor
   * @param lgConfigK the configured Lg K
   * @param tgtHllType the type of target HLL sketch
   */
  HllArray(final int lgConfigK, final TgtHllType tgtHllType) {
    super(lgConfigK, tgtHllType, CurMode.HLL);
    curMin = 0;
    numAtCurMin = 1 << lgConfigK;
    hipAccum = 0;
    kxq0 = 1 << lgConfigK;
    kxq1 = 0;
  }

  /**
   * Copy constructor
   * @param that another HllArray
   */
  HllArray(final HllArray that) {
    super(that);
    curMin = that.curMin;
    numAtCurMin = that.numAtCurMin;
    hipAccum = that.hipAccum;
    kxq0 = that.kxq0;
    kxq1 = that.kxq1;
    hllByteArr = that.hllByteArr.clone(); //that.hllByteArr should never be null.
    auxHashMap = (that.auxHashMap != null) ? that.auxHashMap.copy() : null;
  }

  @Override
  HllArray copyAs(final TgtHllType tgtHllType) {
    if (tgtHllType == this.tgtHllType) {
      return (HllArray)copy();
    }
    if (tgtHllType == HLL_4) {
      return Hll4Array.convertToHll4(this);
    }
    if (tgtHllType == HLL_6) {
      return Hll6Array.convertToHll6(this);
    }
    return Hll8Array.convertToHll8(this);
  }

  @Override
  PairIterator getAuxIterator() {
    if (auxHashMap != null) { return auxHashMap.getIterator(); }
    return null;
  }

  @Override
  int getCount() {
    return -1;
  }

  @Override
  int getCurMin() {
    return curMin;
  }

  @Override
  int getCurrentSerializationBytes() {
    final int auxBytes = (auxHashMap == null) ? 0 : auxHashMap.auxCount << 2;
    return HLL_BYTE_ARRAY_START + hllByteArr.length + auxBytes;
  }

  @Override
  double getEstimate() {
    if (oooFlag) {
      return getCompositeEstimate();
    }
    return hipAccum;
  }

  @Override
  double getHipAccum() {
    return hipAccum;
  }

  @Override
  abstract PairIterator getIterator();

  @Override
  double getLowerBound(final int numStdDev) {
    HllUtil.checkNumStdDev(numStdDev);
    if (lgConfigK > 12) {
      final double tmp;
      if (oooFlag) {
        final double hllNonHipEps =
            (numStdDev * HLL_NON_HIP_RSE_FACTOR) / Math.sqrt(1 << lgConfigK);
        tmp = getCompositeEstimate() / (1.0 + hllNonHipEps);
      } else {
        final double hllHipEps = (numStdDev * HLL_HIP_RSE_FACTOR) / Math.sqrt(1 << lgConfigK);
        tmp =  hipAccum / (1.0 + hllHipEps);
      }
      double numNonZeros = 1 << lgConfigK;
      if (curMin == 0) {
        numNonZeros -= numAtCurMin;
      }
      return Math.max(tmp, numNonZeros);
    }
    //lgConfigK <= 12
    final double re = RelativeErrorTables.getRelErr(false, oooFlag, lgConfigK, numStdDev);
    return ((oooFlag) ? getCompositeEstimate() : hipAccum) / (1.0 + re);
  }

  @Override
  int getMaxCouponArrInts() {
    return -1;
  }

  @Override
  int getNumAtCurMin() {
    return numAtCurMin;
  }

  @Override
  double getRelErr(final int numStdDev) {
    HllUtil.checkNumStdDev(numStdDev);
    if (lgConfigK <= 12) {
      return RelativeErrorTables.getRelErr(true, oooFlag, lgConfigK, numStdDev);
    }
    final double rseFactor =  (oooFlag) ? HLL_NON_HIP_RSE_FACTOR : HLL_HIP_RSE_FACTOR;
    return (rseFactor * numStdDev) / Math.sqrt(1 << lgConfigK);
  }

  @Override
  double getRelErrFactor(final int numStdDev) {
    HllUtil.checkNumStdDev(numStdDev);
    if (lgConfigK <= 12) {
      return RelativeErrorTables.getRelErr(true, oooFlag, lgConfigK, numStdDev)
          * Math.sqrt(1 << lgConfigK);
    }
    final double rseFactor =  (oooFlag) ? HLL_NON_HIP_RSE_FACTOR : HLL_HIP_RSE_FACTOR;
    return rseFactor * numStdDev;
  }

  @Override
  double getUpperBound(final int numStdDev) {
    HllUtil.checkNumStdDev(numStdDev);
    if (lgConfigK > 12) {
      if (oooFlag) {
        final double hllNonHipEps =
            (numStdDev * HLL_NON_HIP_RSE_FACTOR) / Math.sqrt(1 << lgConfigK);
        return getCompositeEstimate() / (1.0 - hllNonHipEps);
      }
      final double hllHipEps = (numStdDev * HLL_HIP_RSE_FACTOR) / Math.sqrt(1 << lgConfigK);
      return hipAccum / (1.0 - hllHipEps);
    }
    //lgConfigK <= 12
    final double re = RelativeErrorTables.getRelErr(true, oooFlag, lgConfigK, numStdDev);
    return ((oooFlag) ? getCompositeEstimate() : hipAccum) / (1.0 + re);
  }

  @Override
  boolean isEmpty() {
    return (curMin == 0) && (numAtCurMin == (1 << lgConfigK));
  }

  @Override
  void putHipAccum(final double value) {
    hipAccum = value;
  }

  @Override
  abstract byte[] toCompactByteArray();

  /**
   * HIP and KxQ incremental update.
   * @param oldValue old value
   * @param newValue new value
   */
  //In C: again-two-registers.c Lines 851 to 871
  void hipAndKxQIncrementalUpdate(final int oldValue, final int newValue) {
    assert newValue > oldValue;
    final int configK = 1 << lgConfigK;
    //update hipAccum BEFORE updating kxq0 and kxq1
    hipAccum += configK / (kxq0 + kxq1);
    //update kxq0 and kxq1; subtract first, then add.
    if (oldValue < 32) { kxq0 -= invPow2(oldValue); }
    else               { kxq1 -= invPow2(oldValue); }
    if (newValue < 32) { kxq0 += invPow2(newValue); }
    else               { kxq1 += invPow2(newValue); }
  }

  static final void checkPreamble(final Memory mem, final Object memArr, final long memAdd) {
    final int memPreInts = extractPreInts(memArr, memAdd);
    final int serVer = extractSerVer(memArr, memAdd);
    final int famId = extractFamilyId(memArr, memAdd);
    if ( (memPreInts != HLL_PREINTS) || (serVer != SER_VER) || (famId != FAMILY_ID) ) {
      badPreambleState(mem);
    }
  }

  //In C: again-two-registers.c hhb_get_raw_estimate L1167
  private static final double getRawEstimate(final int lgConfigK, final double kxqSum) {
    final int configK = 1 << lgConfigK;
    final double correctionFactor;
    if (lgConfigK == 4) { correctionFactor = 0.673; }
    else if (lgConfigK == 5) { correctionFactor = 0.697; }
    else if (lgConfigK == 6) { correctionFactor = 0.709; }
    else { correctionFactor = 0.7213 / (1.0 + (1.079 / configK)); }
    final double hyperEst = (correctionFactor * configK * configK) / kxqSum;
    return hyperEst;
  }

  /**
   * This is the (non-HIP) estimator.
   * It is called "composite" because multiple estimators are pasted together.
   * @return the composite estimate
   */
  //In C: again-two-registers.c hhb_get_composite_estimate L1489
  // Make package private to allow testing.
  @Override
  double getCompositeEstimate() {
    final double rawEst = getRawEstimate(lgConfigK, kxq0 + kxq1);

    final double[] xArr = CompositeInterpolationXTable.xArrs[lgConfigK - MIN_LOG_K];
    final double yStride = CompositeInterpolationXTable.yStrides[lgConfigK - MIN_LOG_K];
    final int xArrLen = xArr.length;

    if (rawEst < xArr[0]) { return 0; }

    final int xArrLenM1 = xArrLen - 1;

    if (rawEst > xArr[xArrLenM1]) {
      final double finalY = yStride * (xArrLenM1);
      final double factor = finalY / xArr[xArrLenM1];
      return rawEst * factor;
    }

    final double adjEst =
        CubicInterpolation.usingXArrAndYStride(xArr, yStride, rawEst);

    // We need to completely avoid the linear_counting estimator if it might have a crazy value.
    // Empirical evidence suggests that the threshold 3*k will keep us safe if 2^4 <= k <= 2^21.

    if (adjEst > (3 << lgConfigK)) { return adjEst; }
    //Alternate call
    //if ((adjEst > (3 << lgConfigK)) || ((curMin != 0) || (numAtCurMin == 0)) ) { return adjEst; }

    final double linEst = getHllBitMapEstimate(lgConfigK, curMin, numAtCurMin);

    // Bias is created when the value of an estimator is compared with a threshold to decide whether
    // to use that estimator or a different one.
    // We conjecture that less bias is created when the average of the two estimators
    // is compared with the threshold. Empirical measurements support this conjecture.

    final double avgEst = (adjEst + linEst) / 2.0;

    // The following constants comes from empirical measurements of the crossover point
    // between the average error of the linear estimator and the adjusted hll estimator
    double crossOver = 0.64;
    if (lgConfigK == 4)      { crossOver = 0.718; }
    else if (lgConfigK == 5) { crossOver = 0.672; }

    return (avgEst > (crossOver * (1 << lgConfigK))) ? adjEst : linEst;
  }

  /**
   * Estimator when N is small, roughly less than k log(k).
   * Refer to Wikipedia: Coupon Collector Problem
   * @return the very low range estimate
   */
  //In C: again-two-registers.c hhb_get_improved_linear_counting_estimate L1274
  private static final double getHllBitMapEstimate(
      final int lgConfigK, final int curMin, final int numAtCurMin) {
    final int configK = 1 << lgConfigK;
    final int numUnhitBuckets =  (curMin == 0) ? numAtCurMin : 0;

    //This will eventually go away.
    if (numUnhitBuckets == 0) {
      return configK * Math.log(configK / 0.5);
    }

    final int numHitBuckets = configK - numUnhitBuckets;
    return HarmonicNumbers.getBitMapEstimate(configK, numHitBuckets);
  }

}
