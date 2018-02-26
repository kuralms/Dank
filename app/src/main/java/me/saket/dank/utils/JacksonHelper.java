package me.saket.dank.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import net.dean.jraw.models.Thing;

import java.io.IOException;
import java.io.InputStream;

import io.reactivex.exceptions.Exceptions;
import timber.log.Timber;

/**
 * Utility methods for dealing with JSON using Jackson. This class uses {@link JsonNode}
 * because every object in JRAW is a wrapper around a JsonNode.
 */
public class JacksonHelper {

  private final ObjectMapper objectMapper;
  private final ObjectWriter jsonPrinter;

  public JacksonHelper(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.jsonPrinter = objectMapper.writer().withDefaultPrettyPrinter();
  }

  public <T extends Thing> String toJson(T objectWithDataNode) {
    // The toString() method doc suggests to use writeValueAsStringInstead,
    return toJson(objectWithDataNode.getDataNode());
  }

  public <T> String toJson(T object) {
    if (object instanceof Thing) {
      throw new UnsupportedOperationException("Pass object's dataNode instead");
    }

    try {
      return jsonPrinter.writeValueAsString(object);
    } catch (JsonProcessingException e) {
      throw Exceptions.propagate(e);
    }
  }

  public JsonNode parseJsonNode(String json) {
    try {
      return objectMapper.readTree(json);
    } catch (IOException e) {
      Timber.e(e, "Couldn't deserialize json: %s", json);
      throw Exceptions.propagate(e);
    }
  }

  public <T> T fromJson(InputStream jsonInputStream, Class<T> dataClass) {
    try {
      return objectMapper.readValue(jsonInputStream, dataClass);

    } catch (IOException e) {
      Timber.e(e, "Couldn't deserialize jsonInputStream");
      throw Exceptions.propagate(e);
    }
  }

  public <T> T fromJson(String json, TypeReference<T> typeReference) {
    try {
      return objectMapper.readValue(json, typeReference);
    } catch (IOException e) {
      Timber.e(e, "Couldn't deserialize json: %s", json);
      throw Exceptions.propagate(e);
    }
  }

  public <T> T fromJson(String json, Class<T> dataClass) {
    try {
      return objectMapper.readValue(json, dataClass);
    } catch (IOException e) {
      Timber.e(e, "Couldn't deserialize json: %s", json);
      throw Exceptions.propagate(e);
    }
  }
}
