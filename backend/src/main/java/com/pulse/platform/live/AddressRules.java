package com.pulse.platform.live;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which addresses live connectors may reach (task 24.3, design.md decision 3), shared by
 * {@link BaseUrls} (on save) and {@link GuardedHttp} (on every request).
 *
 * <ul>
 *   <li><b>Never</b>: unspecified ({@code 0.0.0.0}, {@code ::}), multicast, link-local
 *       ({@code 169.254.0.0/16}, {@code fe80::/10}) and the cloud metadata endpoints, even when
 *       configured literally.</li>
 *   <li><b>Only when configured literally</b>: loopback ({@code 127.0.0.0/8}, {@code ::1}); a host
 *       <em>name</em> that resolves there is refused (DNS rebinding).</li>
 *   <li>Everything else, RFC 1918 LAN ranges included, is reachable, but only on the exact origin a
 *       connector declares.</li>
 * </ul>
 */
final class AddressRules
{
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** Metadata endpoints outside link-local: Alibaba Cloud, AWS IPv6. */
    private static final Set<String> METADATA = Set.of("100.100.100.200", "fd00:ec2:0:0:0:0:0:254");

    private AddressRules()
    {
    }

    /** True for an IPv4 dotted quad or a bracketed (or bare) IPv6 literal; never triggers DNS. */
    static boolean isLiteral(String host)
    {
        String h = unbracket(host);

        return IPV4.matcher(h).matches() || h.contains(":");
    }

    /** The address of a literal host, without DNS; null when {@code host} is not a valid literal. */
    static InetAddress literal(String host)
    {
        if (!isLiteral(host))
        {
            return null;
        }

        String h = unbracket(host);

        if (IPV4.matcher(h).matches())
        {
            for (String part : h.split("\\."))
            {
                if (Integer.parseInt(part) > 255)
                {
                    return null;
                }
            }
        }

        try
        {
            // a literal is parsed, not resolved
            return InetAddress.getByName(h);
        }
        catch (UnknownHostException e)
        {
            return null;
        }
    }

    /** Refused even when configured literally. */
    static boolean never(InetAddress address)
    {
        return address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress()
                || METADATA.contains(address.getHostAddress().toLowerCase(Locale.ROOT))
                || (address instanceof Inet6Address && address.getHostAddress().toLowerCase(Locale.ROOT)
                        .startsWith("fe80"));
    }

    /** Loopback: allowed only as a literal configured host. */
    static boolean loopback(InetAddress address)
    {
        return address.isLoopbackAddress();
    }

    /** Refused when reached through a host name. */
    static boolean blockedByName(InetAddress address)
    {
        return never(address) || loopback(address);
    }

    /** {@code localhost} and {@code *.localhost} name the loopback interface by definition. */
    static boolean loopbackName(String host)
    {
        String h = host.toLowerCase(Locale.ROOT);

        return h.equals("localhost") || h.endsWith(".localhost");
    }

    static String unbracket(String host)
    {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
