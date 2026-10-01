package net.lax1dude.eaglercraft.v1_8.spike;

import java.net.URI;

public final class UriParityMain {
   public static void main(String[] args) throws Exception {
      assertRegistryAuthority("http://...and", "...and");
      assertRegistryAuthority("http://a..b", "a..b");
      assertRegistryAuthority("http://.", ".");
      assertRegistryAuthority("http://-a", "-a");
      assertRegistryAuthority("http://a-", "a-");
      assertRegistryAuthority("http://a.1", "a.1");
      assertRegistryAuthority("http://a.1.", "a.1.");
      assertRegistryAuthority("http://-a.", "-a.");

      assertHost("http://a.", "a.");
      assertHost("http://example.com.", "example.com.");
      assertHost("http://123.", "123.");
      assertHost("http://a.b.", "a.b.");
      assertHost("http://1", "1");

      URI valid = new URI("https://example.com/path");
      if (!"example.com".equals(valid.getHost()) || !"https".equals(valid.getScheme())) {
         throw new AssertionError("valid URI parsing changed: " + valid);
      }
      System.out.println("URI_PARITY_RESULT=PASS");
   }

   private static void assertHost(String value, String host) throws Exception {
      URI uri = new URI(value);
      if (!value.equals(uri.toString()) || !host.equals(uri.getHost())) {
         throw new AssertionError("host mismatch: " + value + " -> " + uri.getHost());
      }
   }

   private static void assertRegistryAuthority(String value, String authority) throws Exception {
      URI uri = new URI(value);
      if (!value.equals(uri.toString()) || !authority.equals(uri.getRawAuthority()) || uri.getHost() != null) {
         throw new AssertionError("registry authority mismatch: " + value);
      }
   }
}
