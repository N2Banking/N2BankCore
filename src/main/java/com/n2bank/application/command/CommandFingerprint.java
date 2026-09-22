package com.n2bank.application.command;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Creates unambiguous fingerprints by length-prefixing every canonical field. */
final class CommandFingerprint {
  private CommandFingerprint() {}

  static String sha256(BankOperationType operationType, String... fields) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, operationType.name());
      for (String field : fields) {
        update(digest, field);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static void update(MessageDigest digest, String field) {
    byte[] value = field.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
    digest.update(value);
  }
}
