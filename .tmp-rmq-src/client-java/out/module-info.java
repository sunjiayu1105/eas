/**
 * Defines the Gson serialization/deserialization API.
 * @since 2.8.6
 */
module org.apache.rocketmq.shaded.com.google.gson {
	exports org.apache.rocketmq.shaded.com.google.gson;
	exports org.apache.rocketmq.shaded.com.google.gson.annotations;
	exports org.apache.rocketmq.shaded.com.google.gson.reflect;
	exports org.apache.rocketmq.shaded.com.google.gson.stream;

	// Optional dependency on java.sql
	requires static java.sql;

	// Optional dependency on jdk.unsupported for JDK's sun.misc.Unsafe
	requires static jdk.unsupported;
}
