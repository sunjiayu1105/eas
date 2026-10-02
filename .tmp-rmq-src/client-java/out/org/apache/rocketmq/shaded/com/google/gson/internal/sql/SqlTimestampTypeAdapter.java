package org.apache.rocketmq.shaded.com.google.gson.internal.sql;

import org.apache.rocketmq.shaded.com.google.gson.Gson;
import org.apache.rocketmq.shaded.com.google.gson.TypeAdapter;
import org.apache.rocketmq.shaded.com.google.gson.TypeAdapterFactory;
import org.apache.rocketmq.shaded.com.google.gson.reflect.TypeToken;
import org.apache.rocketmq.shaded.com.google.gson.stream.JsonReader;
import org.apache.rocketmq.shaded.com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.Date;

class SqlTimestampTypeAdapter extends TypeAdapter<Timestamp> {
  static final TypeAdapterFactory FACTORY = new TypeAdapterFactory() {
    @SuppressWarnings("unchecked") // we use a runtime check to make sure the 'T's equal
    @Override public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
      if (typeToken.getRawType() == Timestamp.class) {
        final TypeAdapter<Date> dateTypeAdapter = gson.getAdapter(Date.class);
        return (TypeAdapter<T>) new SqlTimestampTypeAdapter(dateTypeAdapter);
      } else {
        return null;
      }
    }
  };

  private final TypeAdapter<Date> dateTypeAdapter;

  private SqlTimestampTypeAdapter(TypeAdapter<Date> dateTypeAdapter) {
    this.dateTypeAdapter = dateTypeAdapter;
  }

  @Override
  public Timestamp read(JsonReader in) throws IOException {
    Date date = dateTypeAdapter.read(in);
    return date != null ? new Timestamp(date.getTime()) : null;
  }

  @Override
  public void write(JsonWriter out, Timestamp value) throws IOException {
    dateTypeAdapter.write(out, value);
  }
}
