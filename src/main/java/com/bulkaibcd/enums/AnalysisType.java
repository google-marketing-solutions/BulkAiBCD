package com.bulkaibcd.enums;

/** Enumeration representing the breadth of an analysis run. */
public enum AnalysisType {
  /** Evaluates all four ABCD dimensions (Attract, Brand, Connect, Direct) + Asset Name. */
  STANDARD,

  /** Evaluates only the two cheapest-to-reason-about dimensions (Attract, Brand) + Asset Name. */
  LIGHT
}
