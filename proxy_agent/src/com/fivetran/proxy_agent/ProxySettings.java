package com.fivetran.proxy_agent;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/** Applies the manager environment settings to JDK proxy properties. */
final class ProxySettings {
    private static final String SOCKS_HOST = "socksProxyHost";
    private static final String SOCKS_PORT = "socksProxyPort";
    private static final String SOCKS_VERSION = "socksProxyVersion";
    private static final String INTEGRATED_SCHEMES = "NTLM,Negotiate,Kerberos";

    private ProxySettings() {}

    /** Translates environment settings before the manager opens any HTTP connections. */
    static void configure(String http, String https, String exclusions) {
        if (Stream.of("http.proxyHost", "https.proxyHost", SOCKS_HOST)
                .anyMatch(property -> !System.getProperty(property, "").isEmpty())
                || (http.isEmpty() && https.isEmpty())) {
            return;
        }
        var httpProxy = http.isEmpty() ? null : parse(http);
        var httpsProxy = https.isEmpty() ? null : parse(https);
        var httpSocks = isSocks(httpProxy);
        var httpsSocks = isSocks(httpsProxy);
        if (httpSocks && httpsSocks
                && (!httpProxy.scheme().equals(httpsProxy.scheme())
                    || (httpProxy.host().equalsIgnoreCase(httpsProxy.host()) && httpProxy.port() == httpsProxy.port()
                        && !Objects.equals(httpProxy.userInfo(), httpsProxy.userInfo())))) {
            throw new ConfigurationException("Conflicting SOCKS versions or credentials");
        }

        var patterns = Arrays.stream(exclusions.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty() && !value.contains("/") && !value.contains("|"))
                .map(value -> value.startsWith(".") ? value.substring(1) : value)
                .filter(value -> !value.isEmpty())
                .flatMap(value -> value.equals("*") ? Stream.of(value) : Stream.of(value, "*." + value))
                .toList();
        var bypass = String.join("|", patterns);
        System.getProperties().putIfAbsent("http.nonProxyHosts", bypass);
        System.getProperties().putIfAbsent("socksNonProxyHosts", bypass);
        configureHttp("http", httpProxy);
        configureHttp("https", httpsProxy);
        if (httpSocks || httpsSocks) {
            var socks = Objects.requireNonNull(httpSocks ? httpProxy : httpsProxy);
            System.setProperty(SOCKS_HOST, socks.host());
            System.setProperty(SOCKS_PORT, Integer.toString(socks.port()));
            System.setProperty(SOCKS_VERSION, socks.scheme().equals("socks4") ? "4" : "5");
            // The JDK has one SOCKS endpoint for both protocols. Preserve distinct routes if needed.
            if (httpProxy == null || httpsProxy == null
                    || (httpSocks && httpsSocks && !httpProxy.equals(httpsProxy))) {
                configureSocksRoutes(httpProxy, httpsProxy);
            }
        }
        if (http.contains("@") || https.contains("@")) {
            disableIntegratedAuth("jdk.http.auth.tunneling.disabledSchemes");
            disableIntegratedAuth("jdk.http.auth.proxying.disabledSchemes");
            Authenticator.setDefault(authenticator(httpProxy, httpsProxy));
        }
    }

    private static boolean isSocks(@Nullable ProxyAddress proxy) {
        return proxy != null && !proxy.scheme().equals("http");
    }

    private static void configureHttp(String protocol, @Nullable ProxyAddress proxy) {
        if (proxy != null && proxy.scheme().equals("http")) {
            System.setProperty(protocol + ".proxyHost", proxy.host());
            System.setProperty(protocol + ".proxyPort", Integer.toString(proxy.port()));
        }
    }

    private static void configureSocksRoutes(@Nullable ProxyAddress http, @Nullable ProxyAddress https) {
        var defaults = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                var selected = defaults.select(uri);
                var route = switch (uri.getScheme().toLowerCase(Locale.ROOT)) {
                    case "http" -> http;
                    case "https" -> https;
                    default -> null;
                };
                if (selected.get(0).type() != Proxy.Type.SOCKS) {
                    return selected;
                }
                if (route == null) {
                    return List.of(Proxy.NO_PROXY);
                }
                var address = InetSocketAddress.createUnresolved(route.host(), route.port());
                return selected.get(0).address().equals(address) ? selected : List.of(new Proxy(Proxy.Type.SOCKS, address));
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException exception) {
                defaults.connectFailed(uri, address, exception);
            }
        });
    }

    private static void disableIntegratedAuth(String property) {
        var disabled = System.getProperty(property, "");
        System.setProperty(property, disabled.isEmpty() ? INTEGRATED_SCHEMES : disabled + "," + INTEGRATED_SCHEMES);
    }

    private static ProxyAddress parse(String address) throws ConfigurationException {
        try {
            var uri = URI.create(address.contains("://") ? address : "http://" + address);
            var scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            var host = uri.getHost();
            if (host == null || uri.getPort() == 0 || uri.getPort() > 65535) {
                throw new ConfigurationException("Invalid proxy URL");
            }
            var port = uri.getPort();
            var credentials = uri.getRawUserInfo();
            return switch (scheme) {
                case "http" -> new ProxyAddress(scheme, host, port < 0 ? 80 : port, credentials);
                case "socks", "socks5" -> new ProxyAddress("socks5", host, port < 0 ? 1080 : port, credentials);
                case "socks4" -> {
                    if (credentials != null) {
                        throw new ConfigurationException("SOCKS4 proxy URLs use the JDK's local username");
                    }
                    yield new ProxyAddress(scheme, host, port < 0 ? 1080 : port, null);
                }
                case "https" -> throw new ConfigurationException("The JDK does not support HTTPS proxy URLs");
                case "socks4a", "socks5h" ->
                        throw new ConfigurationException("The JDK cannot force proxy-side DNS for SOCKS URLs");
                default -> throw new ConfigurationException("Unsupported proxy URL scheme");
            };
        } catch (IllegalArgumentException e) {
            // URI diagnostics can contain the proxy password.
            throw new ConfigurationException("Invalid proxy URL");
        }
    }

    private static Authenticator authenticator(@Nullable ProxyAddress http, @Nullable ProxyAddress https) {
        var previous = Authenticator.getDefault();
        return new Authenticator() {
            @Override
            protected @Nullable PasswordAuthentication getPasswordAuthentication() {
                var url = getRequestingURL();
                ProxyAddress proxy = null;
                if ("SOCKS5".equals(getRequestingProtocol()) && url == null) {
                    proxy = isSocks(http) && matches(http) ? http : isSocks(https) && matches(https) ? https : null;
                } else if (getRequestorType() == RequestorType.PROXY && url != null) {
                    proxy = url.getProtocol().equals("https") ? https : http;
                    if (isSocks(proxy) || !matches(proxy)) {
                        proxy = null;
                    }
                }
                if (proxy != null && proxy.userInfo() != null) {
                    var credentials = proxy.userInfo().split(":", 2);
                    var password = credentials.length > 1 ? decode(credentials[1]) : "";
                    return new PasswordAuthentication(decode(credentials[0]), password.toCharArray());
                }
                return previous == null ? null : previous.requestPasswordAuthenticationInstance(getRequestingHost(),
                        getRequestingSite(), getRequestingPort(), getRequestingProtocol(), getRequestingPrompt(),
                        getRequestingScheme(), url, getRequestorType());
            }

            private boolean matches(@Nullable ProxyAddress proxy) {
                return proxy != null && proxy.host().equalsIgnoreCase(getRequestingHost())
                        && proxy.port() == getRequestingPort();
            }
        };
    }

    private static String decode(String value) {
        return URLDecoder.decode(value.replace("+", "%2B"), UTF_8);
    }

    private record ProxyAddress(String scheme, String host, int port, @Nullable String userInfo) {}

    /** A sanitized proxy setting error that can be displayed by the manager. */
    static final class ConfigurationException extends RuntimeException {
        private ConfigurationException(String message) {
            super(message);
        }
    }
}
