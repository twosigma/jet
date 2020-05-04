package org.eclipse.jetty.http2.hpack;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.StringUtil;

/**
 * Helper class to simplify handling the jetty upgrade.
 */
public class Utils {
    /**
     * Copied from org.eclipse.jetty.http.HttpField#getLowerCaseName
     */
    static String getLowerCaseFieldName(final HttpField httpField) {
        final HttpHeader _header = httpField.getHeader();
        final String _name = httpField.getName();
        return _header != null ? StringUtil.asciiToLowerCase(_header.asString()) : StringUtil.asciiToLowerCase(_name);
    }
}
