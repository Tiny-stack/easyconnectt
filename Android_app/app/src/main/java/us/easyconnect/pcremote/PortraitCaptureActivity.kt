package us.easyconnect.pcremote

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * zxing-android-embedded's default [CaptureActivity] opens in landscape (its
 * CaptureManager forces the sensor orientation). We subclass it purely so we
 * can pin the orientation to portrait via the manifest entry for this class,
 * then point [ScanOptions.setCaptureActivity] at it. No behavior change beyond
 * the locked orientation.
 */
class PortraitCaptureActivity : CaptureActivity()
