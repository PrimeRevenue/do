package data_objects.drivers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.jruby.Ruby;
import org.jruby.RubyBigDecimal;
import org.jruby.RubyBignum;
import org.jruby.RubyClass;
import org.jruby.RubyFixnum;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyNumeric;
import org.jruby.RubyObjectAdapter;
import org.jruby.RubyProc;
import org.jruby.RubyString;
import org.jruby.RubyTime;
import org.jruby.exceptions.RaiseException;
import org.jruby.javasupport.JavaEmbedUtils;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.marshal.UnmarshalStream;
import org.jruby.util.ByteList;

import data_objects.RubyType;

/**
 *
 * @author alexbcoles
 */
public abstract class AbstractDriverDefinition implements DriverDefinition {

    protected static final RubyObjectAdapter api = JavaEmbedUtils
            .newObjectAdapter();

    private final String scheme;
    private final String moduleName;

    protected AbstractDriverDefinition(String scheme, String moduleName) {
        this.scheme = scheme;
        this.moduleName = moduleName;
    }

    public String getModuleName() {
        return this.moduleName;
    }

    public String getErrorName() {
        return this.moduleName + "Error";
    }

    @SuppressWarnings("unchecked")
    public final URI parseConnectionURI(IRubyObject connection_uri)
            throws URISyntaxException, UnsupportedEncodingException {
        URI uri;

        if ("DataObjects::URI".equals(connection_uri.getType().getName())) {
            String query = null;
            StringBuffer userInfo = new StringBuffer();

            verifyScheme(stringOrNull(api.callMethod(connection_uri, "scheme")));

            String user = stringOrNull(api.callMethod(connection_uri, "user"));
            String password = stringOrNull(api.callMethod(connection_uri,
                    "password"));
            String host = stringOrNull(api.callMethod(connection_uri, "host"));
            int port = intOrMinusOne(api.callMethod(connection_uri, "port"));
            String path = stringOrNull(api.callMethod(connection_uri, "path"));
            IRubyObject query_values = api.callMethod(connection_uri, "query");
            String fragment = stringOrNull(api.callMethod(connection_uri,
                    "fragment"));

            if (user != null && !"".equals(user)) {
                userInfo.append(user);
                if (password != null && !"".equals(password)) {
                    userInfo.append(":").append(password);
                }
            }

            if (query_values.isNil()) {
                query = null;
            } else if (query_values instanceof RubyHash) {
                query = mapToQueryString(query_values.convertToHash());
            } else {
                query = api.callMethod(query_values, "to_s").asJavaString();
            }

            if (host != null && !"".equals(host)) {
                // a client/server database (e.g. MySQL, PostgreSQL, MS
                // SQLServer)
                uri = new URI(this.scheme, userInfo.toString(), host, port,
                        path, query, fragment);
            } else {
                // an embedded / file-based database (e.g. SQLite3, Derby
                // (embedded mode), HSQLDB - use opaque uri
                uri = new java.net.URI(scheme, path, fragment);
            }
        } else {
            // If connection_uri comes in as a string, we just pass it
            // through
            uri = new URI(connection_uri.asJavaString());
        }
        return uri;
    }

    protected void verifyScheme(String scheme) {
        if (!this.scheme.equals(scheme)) {
            throw new RuntimeException("scheme mismatch, expected: "
                    + this.scheme + " but got: " + scheme);
        }
    }

    /**
     * Convert a map of key/values to a URI query string
     *
     * @param map
     * @return
     * @throws java.io.UnsupportedEncodingException
     */
    private String mapToQueryString(Map<Object, Object> map)
            throws UnsupportedEncodingException {
        Iterator it = map.entrySet().iterator();
        StringBuffer querySb = new StringBuffer();
        while (it.hasNext()) {
            Map.Entry pairs = (Map.Entry) it.next();
            String key = (pairs.getKey() != null) ? pairs.getKey().toString()
                    : "";
            String value = (pairs.getValue() != null) ? pairs.getValue()
                    .toString() : "";
            querySb.append(java.net.URLEncoder.encode(key, "UTF-8"))
                    .append("=");
            querySb.append(java.net.URLEncoder.encode(value, "UTF-8"));
        }
        return querySb.toString();
    }

    public RaiseException newDriverError(Ruby runtime, String message) {
        RubyClass driverError = runtime.getClass(getErrorName());
        return new RaiseException(runtime, driverError, message, true);
    }

    public RaiseException newDriverError(Ruby runtime, SQLException exception) {
        return newDriverError(runtime, exception, null);
    }

    public RaiseException newDriverError(Ruby runtime, SQLException exception,
            java.sql.Statement statement) {
        RubyClass driverError = runtime.getClass(getErrorName());
        int code = exception.getErrorCode();
        StringBuffer sb = new StringBuffer("(");

        // Append the Vendor Code, if there is one
        // TODO: parse vendor exception codes
        // TODO: replace 'vendor' with vendor name
        if (code > 0)
            sb.append("vendor_errno=").append(code).append(", ");
        sb.append("sql_state=").append(exception.getSQLState()).append(") ");
        sb.append(exception.getLocalizedMessage());
        // TODO: delegate to the DriverDefinition for this
        if (statement != null)
            sb.append("\nQuery: ").append(statement.toString());

        return new RaiseException(runtime, driverError, sb.toString(), true);
    }

    public RubyObjectAdapter getObjectAdapter() {
        return api;
    }

    public final IRubyObject getTypecastResultSetValue(Ruby runtime,
            ResultSet rs, int col, RubyType type) throws SQLException,
            IOException {
        // TODO assert to needs to be turned on with the java call
        // better throw something
        assert (type != null); // this method does not expect a null Ruby Type
        if (rs == null) {// || rs.wasNull()) {
            return runtime.getNil();
        }

        return doGetTypecastResultSetValue(runtime, rs, col, type);
    }

    protected IRubyObject doGetTypecastResultSetValue(Ruby runtime,
            ResultSet rs, int col, RubyType type) throws SQLException,
            IOException {
        switch (type) {
        case FIXNUM:
        case INTEGER:
        case BIGNUM:
            // TODO: attempt to make this more granular, depending on the
            // size of the number (?)
            long lng = rs.getLong(col);
            return RubyNumeric.int2fix(runtime, lng);
        case FLOAT:
            return new RubyFloat(runtime, rs.getBigDecimal(col).doubleValue());
        case BIG_DECIMAL:
            return new RubyBigDecimal(runtime, rs.getBigDecimal(col));
        case DATE:
            java.sql.Date date = rs.getDate(col);
            if (date == null) {
                return runtime.getNil();
            }
            return prepareRubyDateFromSqlDate(runtime, new DateTime(date));
        case DATE_TIME:
            java.sql.Timestamp dt = null;
            // DateTimes with all-zero components throw a SQLException with
            // SQLState S1009 in MySQL Connector/J 3.1+
            // See
            // http://dev.mysql.com/doc/refman/5.0/en/connector-j-installing-upgrading.html
            try {
                dt = rs.getTimestamp(col);
            } catch (SQLException sqle) {
            }
            if (dt == null) {
                return runtime.getNil();
            }
            return prepareRubyDateTimeFromSqlTimestamp(runtime,
                    new DateTime(dt));
        case TIME:
            switch (rs.getMetaData().getColumnType(col)) {
            case Types.TIME:
                java.sql.Time tm = rs.getTime(col);
                if (tm == null) {
                    return runtime.getNil();
                }
                return prepareRubyTimeFromSqlTime(runtime, new DateTime(tm));
            case Types.TIMESTAMP:
                java.sql.Time ts = rs.getTime(col);
                if (ts == null) {
                    return runtime.getNil();
                }
                return prepareRubyTimeFromSqlTime(runtime, new DateTime(ts));
            case Types.DATE:
                java.sql.Date da = rs.getDate(col);
                if (da == null) {
                    return runtime.getNil();
                }
                return prepareRubyTimeFromSqlDate(runtime, da);
            default:
                String str = rs.getString(col);
                if (str == null) {
                    return runtime.getNil();
                }
                RubyString return_str = RubyString.newUnicodeString(runtime,
                        str);
                return_str.setTaint(true);
                return return_str;
            }
        case TRUE_CLASS:
            boolean bool = rs.getBoolean(col);
            return runtime.newBoolean(bool);
        case BYTE_ARRAY:
            InputStream binaryStream = rs.getBinaryStream(col);
            ByteList bytes = new ByteList(2048);
            try {
                byte[] buf = new byte[2048];
                for (int n = binaryStream.read(buf); n != -1; n = binaryStream
                        .read(buf)) {
                    bytes.append(buf, 0, n);
                }
            } finally {
                binaryStream.close();
            }
            return api.callMethod(runtime.fastGetModule("Extlib").fastGetClass(
                    "ByteArray"), "new", runtime.newString(bytes));
        case CLASS:
            String classNameStr = rs.getString(col);
            if (classNameStr == null) {
                return runtime.getNil();
            }
            RubyString class_name_str = RubyString.newUnicodeString(runtime, rs
                    .getString(col));
            class_name_str.setTaint(true);
            return api.callMethod(runtime.getObject(), "full_const_get",
                    class_name_str);
        case OBJECT:
            InputStream asciiStream = rs.getAsciiStream(col);
            IRubyObject obj = runtime.getNil();
            try {
                UnmarshalStream ums = new UnmarshalStream(runtime, asciiStream,
                        RubyProc.NEVER);
                obj = ums.unmarshalObject();
            } catch (IOException ioe) {
                // TODO: log this
            }
            return obj;
        case NIL:
            return runtime.getNil();
        case STRING:
        default:
            String str = rs.getString(col);
            if (str == null) {
                return runtime.getNil();
            }
            RubyString return_str = RubyString.newUnicodeString(runtime, str);
            return_str.setTaint(true);
            return return_str;
        }
    }

    // TODO SimpleDateFormat is not threadsafe better use joda classes
    // http://java.sun.com/j2se/1.5.0/docs/api/java/text/SimpleDateFormat.html#synchronization
    // http://joda-time.sourceforge.net/api-release/org/joda/time/DateTime.html
    // private static final DateFormat FORMAT = new SimpleDateFormat(
    // "yyyy-MM-dd HH:mm:ss");

    public void setPreparedStatementParam(PreparedStatement ps,
            IRubyObject arg, int idx) throws SQLException {
        switch (RubyType.getRubyType(arg.getType().getName())) {
        case FIXNUM:
            ps.setInt(idx, Integer.parseInt(arg.toString()));
            break;
        case BIGNUM:
            ps.setLong(idx, ((RubyBignum) arg).getLongValue());
            break;
        case FLOAT:
            ps.setDouble(idx, RubyNumeric.num2dbl(arg));
            break;
        case BIG_DECIMAL:
            ps.setBigDecimal(idx, ((RubyBigDecimal) arg).getValue());
            break;
        case NIL:
            ps.setNull(idx, ps.getParameterMetaData().getParameterType(idx));
            break;
        case TRUE_CLASS:
        case FALSE_CLASS:
            ps.setBoolean(idx, arg.toString().equals("true"));
            break;
        case CLASS:
            ps.setString(idx, arg.toString());
            break;
        case BYTE_ARRAY:
            ps.setBytes(idx, ((RubyString) arg).getBytes());
            break;
        // TODO: add support for ps.setBlob();
        case DATE:
            ps.setDate(idx, java.sql.Date.valueOf(arg.toString()));
            break;
        case TIME:
            DateTime dateTime = ((RubyTime) arg).getDateTime();
            GregorianCalendar cal = dateTime.toGregorianCalendar();
            Timestamp ts;
            if (supportsCalendarsInJDBCPreparedStatement() == true) {
                ts = new Timestamp(dateTime.getMillis());
            } else {
                // XXX ugly workaround for MySQL and Hsqldb
                // use the default timeZone the oposite way these jdbc drivers do it
                long offset = DateTimeZone.getDefault().getOffset(dateTime.getMillis());
                ts = new Timestamp(dateTime.getMillis() - offset);
            }
            ps.setTimestamp(idx, ts, cal);
            break;
        case DATE_TIME:
            String datetime = arg.toString().replace('T', ' ');
            ps.setTimestamp(idx, Timestamp.valueOf(datetime
                    .replaceFirst("[-+]..:..$", "")));
            break;
        default:
            if (arg.toString().indexOf("-") != -1
                    && arg.toString().indexOf(":") != -1) {
                // TODO: improve the above string pattern checking
                // Handle date patterns in strings
                try {
                    DateTime timestamp = DATE_TIME_FORMAT.parseDateTime(arg
                            .asJavaString().replace('T', ' '));
                    ps.setTimestamp(idx, new Timestamp(timestamp.getMillis()));
                } catch (IllegalArgumentException ex) {
                    ps.setString(idx, api.convertToRubyString(arg)
                            .getUnicodeValue());
                }
            } else if (arg.toString().indexOf(":") != -1
                    && arg.toString().length() == 8) {
                // Handle time patterns in strings
                ps.setTime(idx, java.sql.Time.valueOf(arg.asJavaString()));
            } else {
                Integer jdbcTypeId = null;
                try {
                    jdbcTypeId = ps.getMetaData().getColumnType(idx);
                } catch (Exception ex) {
                }

                if (jdbcTypeId == null) {
                    ps.setString(idx, api.convertToRubyString(arg)
                            .getUnicodeValue());
                } else {
                    // TODO: Here comes conversions like '.execute_reader("2")'
                    // It definitly needs to be refactored...
                    try {
                        if (jdbcTypeId == Types.VARCHAR) {
                            ps.setString(idx, api.convertToRubyString(arg)
                                    .getUnicodeValue());
                        } else if (jdbcTypeId == Types.INTEGER) {
                            ps.setObject(idx, Integer.valueOf(arg.toString()),
                                    jdbcTypeId);
                        } else {
                            // I'm not sure is it correct in 100%
                            ps.setString(idx, api.convertToRubyString(arg)
                                    .getUnicodeValue());
                        }
                    } catch (NumberFormatException ex) { // i.e
                        // Integer.valueOf
                        ps.setString(idx, api.convertToRubyString(arg)
                                .getUnicodeValue());
                    }
                }
            }
        }
    }

    public abstract boolean supportsJdbcGeneratedKeys();

    public abstract boolean supportsJdbcScrollableResultSets();

    public boolean supportsConnectionEncodings() {
        return false;
    }

    public boolean supportsConnectionPrepareStatementMethodWithGKFlag() {
        return true;
    }

    public boolean supportsCalendarsInJDBCPreparedStatement(){
        return true;
    }

    public ResultSet getGeneratedKeys(Connection connection) {
        return null;
    }

    public Properties getDefaultConnectionProperties() {
        return new Properties();
    }

    public void setEncodingProperty(Properties props, String encodingName) {
        // do nothing
    }

    public String quoteString(String str) {
        StringBuffer quotedValue = new StringBuffer(str.length() + 2);
        quotedValue.append("\'");
        quotedValue.append(str);
        quotedValue.append("\'");
        return quotedValue.toString();
    }

    public String toString(PreparedStatement ps) {
        return ps.toString();
    }

    protected static IRubyObject prepareRubyDateTimeFromSqlTimestamp(
            Ruby runtime, DateTime stamp) {

        if (stamp.getMillis() == 0) {
            return runtime.getNil();
        }

        int zoneOffset = stamp.getZone().getOffset(stamp.getMillis()) / 3600000; // regCalendar.get(Calendar.ZONE_OFFSET)
        // /
        // 3600000;
        RubyClass klazz = runtime.fastGetClass("DateTime");

        IRubyObject rbOffset = runtime.fastGetClass("Rational").callMethod(
                runtime.getCurrentContext(),
                "new",
                new IRubyObject[] { runtime.newFixnum(zoneOffset),
                        runtime.newFixnum(24) });

        return klazz.callMethod(runtime.getCurrentContext(), "civil",
                new IRubyObject[] { runtime.newFixnum(stamp.getYear()),// gregCalendar.get(Calendar.YEAR)),
                        runtime.newFixnum(stamp.getMonthOfYear()),// month),
                        runtime.newFixnum(stamp.getDayOfMonth()), // gregCalendar.get(Calendar.DAY_OF_MONTH)),
                        runtime.newFixnum(stamp.getHourOfDay()), // gregCalendar.get(Calendar.HOUR_OF_DAY)),
                        runtime.newFixnum(stamp.getMinuteOfHour()), // gregCalendar.get(Calendar.MINUTE)),
                        runtime.newFixnum(stamp.getSecondOfMinute()), // gregCalendar.get(Calendar.SECOND)),
                        rbOffset });
    }

    protected static IRubyObject prepareRubyTimeFromSqlTime(Ruby runtime,
            DateTime time) {

        if (time.getMillis() + 3600000 == 0) {
            return runtime.getNil();
        }

        RubyTime rbTime = RubyTime.newTime(runtime, time);
        rbTime.extend(new IRubyObject[] { runtime.getModule("TimeFormatter") });
        return rbTime;
    }

    protected static IRubyObject prepareRubyTimeFromSqlDate(Ruby runtime,
            Date date) {

        if (date.getTime() + 3600000 == 0) {
            return runtime.getNil();
        }
        RubyTime rbTime = RubyTime.newTime(runtime, date.getTime());
        rbTime.extend(new IRubyObject[] { runtime.getModule("TimeFormatter") });
        return rbTime;
    }

    public static IRubyObject prepareRubyDateFromSqlDate(Ruby runtime,
            DateTime date) {

        if (date.getMillis() == 0) {
            return runtime.getNil();
        }

        // TODO
        // gregCalendar.setTime(date.toDate());
        // int month = gregCalendar.get(Calendar.MONTH);
        // month++; // In Calendar January == 0, etc...
        RubyClass klazz = runtime.fastGetClass("Date");
        return klazz.callMethod(runtime.getCurrentContext(), "civil",
                new IRubyObject[] { runtime.newFixnum(date.getYear()),
                        runtime.newFixnum(date.getMonthOfYear()),
                        runtime.newFixnum(date.getDayOfMonth()) });
    }

    private final static DateTimeFormatter DATE_FORMAT = ISODateTimeFormat
            .date();// yyyy-MM-dd
    private final static DateTimeFormatter DATE_TIME_FORMAT = DateTimeFormat
            .forPattern("yyyy-MM-dd HH:mm:ss");
    private final static DateTimeFormatter TIMESTAMP_FORMAT = ISODateTimeFormat
            .dateTime();

    public static DateTime toDate(String date) {
        return DATE_FORMAT.parseDateTime(date.replaceFirst("T.*", ""));
    }

    public static DateTime toTimestamp(String stamp) {
        DateTimeFormatter formatter = stamp.contains("T") ? TIMESTAMP_FORMAT
                : DATE_FORMAT;// "yyyy-MM-dd'T'HH:mm:ssZ" : "yyyy-MM-dd");
        return formatter.parseDateTime(stamp);
    }

    public static DateTime toTime(String time) {
        DateTimeFormatter formatter = time.contains(" ") ? DATE_TIME_FORMAT
                : DATE_FORMAT;
        return formatter.parseDateTime(time);
    }

    private static String stringOrNull(IRubyObject obj) {
        return (!obj.isNil()) ? obj.asJavaString() : null;
    }

    private static int intOrMinusOne(IRubyObject obj) {
        return (!obj.isNil()) ? RubyFixnum.fix2int(obj) : -1;
    }

    // private static Integer integerOrNull(IRubyObject obj) {
    // return (!obj.isNil()) ? RubyFixnum.fix2int(obj) : null;
    // }
}
