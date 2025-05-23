/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.slackerdb.jdbc.util;

import static org.slackerdb.jdbc.util.internal.Nullness.castNonNull;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Serializable;
import java.sql.SQLException;

/**
 * PGobject is a class used to describe unknown types An unknown type is any type that is unknown by
 * JDBC Standards.
 */
public class PGobject implements Serializable, Cloneable {
  protected @Nullable String type;
  protected @Nullable String value;

  /**
   * This is called by org.postgresql.Connection.getObject() to create the object.
   */
  public PGobject() {
  }

  /**
   * This method sets the type of this object.
   *
   * <p>It should not be extended by subclasses, hence it is final</p>
   *
   * @param type a string describing the type of the object
   */
  public final void setType(String type) {
    this.type = type;
  }

  /**
   * This method sets the value of this object. It must be overridden.
   *
   * @param value a string representation of the value of the object
   * @throws SQLException thrown if value is invalid for this type
   */
  public void setValue(@Nullable String value) throws SQLException {
    this.value = value;
  }

  /**
   * As this cannot change during the life of the object, it's final.
   *
   * @return the type name of this object
   */
  public final String getType() {
    return castNonNull(type, "PGobject#type is uninitialized. Please call setType(String)");
  }

  /**
   * This must be overridden, to return the value of the object, in the form required by
   * org.postgresql.
   *
   * @return the value of this object
   */
  public @Nullable String getValue() {
    return value;
  }

  /**
   * Returns true if the current object wraps `null` value.
   * This might be helpful
   *
   * @return true if the current object wraps `null` value.
   */
  public boolean isNull() {
    return getValue() == null;
  }

  /**
   * This must be overridden to allow comparisons of objects.
   *
   * @param obj Object to compare with
   * @return true if the two boxes are identical
   */
  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof PGobject) {
      final Object otherValue = ((PGobject) obj).getValue();

      if (otherValue == null) {
        return getValue() == null;
      }
      return otherValue.equals(getValue());
    }
    return false;
  }

  /**
   * This must be overridden to allow the object to be cloned.
   */
  public Object clone() throws CloneNotSupportedException {
    return super.clone();
  }

  /**
   * This is defined here, so user code need not override it.
   *
   * @return the value of this object, in the syntax expected by org.postgresql
   */
  @Override
  @SuppressWarnings("nullness")
  public String toString() {
    return getValue();
  }

  /**
   * Compute hash. As equals() use only value. Return the same hash for the same value.
   *
   * @return Value hashcode, 0 if value is null {@link java.util.Objects#hashCode(Object)}
   */
  @Override
  public int hashCode() {
    String value = getValue();
    return value != null ? value.hashCode() : 0;
  }

  protected static boolean equals(@Nullable Object a, @Nullable Object b) {
    return a == b || a != null && a.equals(b);
  }
}
