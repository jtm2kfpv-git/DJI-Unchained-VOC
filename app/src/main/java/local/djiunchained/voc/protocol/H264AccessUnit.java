package local.djiunchained.voc.protocol;

public record H264AccessUnit(byte[] data, boolean keyFrame, byte[] sps, byte[] pps) {
}
