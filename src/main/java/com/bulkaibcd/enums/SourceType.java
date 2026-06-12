package com.bulkaibcd.enums;

/** Enumeration representing the origin source type of a video asset. */
public enum SourceType {
  /** Canonical YouTube video URL */
  YOUTUBE,

  /** Google Drive video file or folder link */
  DRIVE,

  /** Direct binary file upload to GCS */
  FILE,

  /** Google Ads video asset ID */
  ID
}
