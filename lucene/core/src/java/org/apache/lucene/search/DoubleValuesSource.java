/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.search;

import java.io.IOException;
import java.util.Objects;
import java.util.function.DoubleToLongFunction;
import java.util.function.LongToDoubleFunction;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.search.comparators.DoubleComparator;
import org.apache.lucene.util.NumericUtils;

/**
 * Base class for producing {@link DoubleValues}
 *
 * <p>To obtain a {@link DoubleValues} object for a leaf reader, clients should call {@link
 * #rewrite(IndexSearcher)} against the top-level searcher, and then call {@link
 * #getValues(LeafReaderContext, DoubleValues)} on the resulting DoubleValuesSource.
 *
 * <p>DoubleValuesSource objects for NumericDocValues fields can be obtained by calling {@link
 * #fromDoubleField(String)}, {@link #fromFloatField(String)}, {@link #fromIntField(String)} or
 * {@link #fromLongField(String)}, or from {@link #fromField(String, LongToDoubleFunction)} if
 * special long-to-double encoding is required.
 *
 * <p>Scores may be used as a source for value calculations by wrapping a {@link Scorer} using
 * {@link #fromScorer(Scorable)} and passing the resulting DoubleValues to {@link
 * #getValues(LeafReaderContext, DoubleValues)}. The scores can then be accessed using the {@link
 * #SCORES} DoubleValuesSource.
 */
public abstract class DoubleValuesSource implements SegmentCacheable {

  /**
   * Returns a {@link DoubleValues} instance for the passed-in LeafReaderContext and scores
   *
   * <p>If scores are not needed to calculate the values (ie {@link #needsScores() returns false},
   * callers may safely pass {@code null} for the {@code scores} parameter.
   */
  public abstract DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores)
      throws IOException;

  /** Return true if document scores are needed to calculate values */
  public abstract boolean needsScores();

  /**
   * An explanation of the value for the named document.
   *
   * @param ctx the readers context to create the {@link Explanation} for.
   * @param docId the document's id relative to the given context's reader
   * @return an Explanation for the value
   * @throws IOException if an {@link IOException} occurs
   */
  public Explanation explain(LeafReaderContext ctx, int docId, Explanation scoreExplanation)
      throws IOException {
    DoubleValues dv =
        getValues(
            ctx,
            DoubleValuesSource.constant(scoreExplanation.getValue().doubleValue())
                .getValues(ctx, null));
    if (dv.advanceExact(docId)) return Explanation.match(dv.doubleValue(), this.toString());
    return Explanation.noMatch(this.toString());
  }

  /**
   * Return a DoubleValuesSource specialised for the given IndexSearcher
   *
   * <p>Implementations should assume that this will only be called once. IndexReader-independent
   * implementations can just return {@code this}
   *
   * <p>Queries that use DoubleValuesSource objects should call rewrite() during {@link
   * Query#createWeight(IndexSearcher, ScoreMode, float)} rather than during {@link
   * Query#rewrite(IndexSearcher)} to avoid IndexReader reference leakage.
   *
   * <p>For the same reason, implementations that cache references to the IndexSearcher should
   * return a new object from this method.
   */
  public abstract DoubleValuesSource rewrite(IndexSearcher reader) throws IOException;

  /**
   * Create a sort field based on the value of this producer
   *
   * @param reverse true if the sort should be decreasing
   */
  public SortField getSortField(boolean reverse) {
    return new DoubleValuesSortField(this, reverse);
  }

  @Override
  public abstract int hashCode();

  @Override
  public abstract boolean equals(Object obj);

  @Override
  public abstract String toString();

  /** Convert to a LongValuesSource by casting the double values to longs */
  public final LongValuesSource toLongValuesSource() {
    return new LongDoubleValuesSource(this);
  }

  /** Convert to {@link LongValuesSource} by calling {@link NumericUtils#doubleToSortableLong} */
  public final LongValuesSource toSortableLongDoubleValuesSource() {
    return new SortableLongDoubleValuesSource(this);
  }

  private static class SortableLongDoubleValuesSource extends LongValuesSource {

    private final DoubleValuesSource inner;

    private SortableLongDoubleValuesSource(DoubleValuesSource inner) {
      this.inner = Objects.requireNonNull(inner);
    }

    @Override
    public LongValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
      DoubleValues in = inner.getValues(ctx, scores);

      return new LongValues() {
        @Override
        public long longValue() throws IOException {
          return NumericUtils.doubleToSortableLong(in.doubleValue());
        }

        @Override
        public boolean advanceExact(int doc) throws IOException {
          return in.advanceExact(doc);
        }
      };
    }

    @Override
    public boolean needsScores() {
      return inner.needsScores();
    }

    @Override
    public int hashCode() {
      return inner.hashCode();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SortableLongDoubleValuesSource that = (SortableLongDoubleValuesSource) o;
      return Objects.equals(inner, that.inner);
    }

    @Override
    public String toString() {
      return "sortableLong(" + inner.toString() + ")";
    }

    @Override
    public LongValuesSource rewrite(IndexSearcher searcher) throws IOException {
      return inner.rewrite(searcher).toLongValuesSource();
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      return false;
    }
  }

  private static class LongDoubleValuesSource extends LongValuesSource {

    private final DoubleValuesSource inner;

    private LongDoubleValuesSource(DoubleValuesSource inner) {
      this.inner = inner;
    }

    @Override
    public LongValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
      DoubleValues in = inner.getValues(ctx, scores);
      return new LongValues() {
        @Override
        public long longValue() throws IOException {
          return (long) in.doubleValue();
        }

        @Override
        public boolean advanceExact(int doc) throws IOException {
          return in.advanceExact(doc);
        }
      };
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      return inner.isCacheable(ctx);
    }

    @Override
    public boolean needsScores() {
      return inner.needsScores();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      LongDoubleValuesSource that = (LongDoubleValuesSource) o;
      return Objects.equals(inner, that.inner);
    }

    @Override
    public int hashCode() {
      return Objects.hash(inner);
    }

    @Override
    public String toString() {
      return "long(" + inner.toString() + ")";
    }

    @Override
    public LongValuesSource rewrite(IndexSearcher searcher) throws IOException {
      return inner.rewrite(searcher).toLongValuesSource();
    }
  }

  /**
   * Returns a DoubleValues instance for computing the vector similarity score per document against
   * the byte query vector
   *
   * @param ctx the context for which to return the DoubleValues
   * @param queryVector byte query vector
   * @param vectorField knn byte field name
   * @return DoubleValues instance
   * @throws IOException if an {@link IOException} occurs
   */
  public static DoubleValues similarityToQueryVector(
      LeafReaderContext ctx, byte[] queryVector, String vectorField) throws IOException {
    return new ByteVectorSimilarityValuesSource(queryVector, vectorField).getValues(ctx, null);
  }

  /**
   * Returns a DoubleValues instance for computing the vector similarity score per document against
   * the float query vector
   *
   * @param ctx the context for which to return the DoubleValues
   * @param queryVector float query vector
   * @param vectorField knn float field name
   * @return DoubleValues instance
   * @throws IOException if an {@link IOException} occurs
   */
  public static DoubleValues similarityToQueryVector(
      LeafReaderContext ctx, float[] queryVector, String vectorField) throws IOException {
    return new FloatVectorSimilarityValuesSource(queryVector, vectorField).getValues(ctx, null);
  }

  /**
   * Creates a DoubleValuesSource that wraps a generic NumericDocValues field
   *
   * @param field the field to wrap, must have NumericDocValues
   * @param decoder a function to convert the long-valued doc values to doubles
   */
  public static DoubleValuesSource fromField(String field, LongToDoubleFunction decoder) {
    return new FieldValuesSource(field, decoder);
  }

  /** Creates a DoubleValuesSource that wraps a double-valued field */
  public static DoubleValuesSource fromDoubleField(String field) {
    return fromField(field, Double::longBitsToDouble);
  }

  /** Creates a DoubleValuesSource that wraps a float-valued field */
  public static DoubleValuesSource fromFloatField(String field) {
    return fromField(field, (v) -> (double) Float.intBitsToFloat((int) v));
  }

  /** Creates a DoubleValuesSource that wraps a long-valued field */
  public static DoubleValuesSource fromLongField(String field) {
    return fromField(field, (v) -> (double) v);
  }

  /** Creates a DoubleValuesSource that wraps an int-valued field */
  public static DoubleValuesSource fromIntField(String field) {
    return fromLongField(field);
  }

  /**
   * A DoubleValuesSource that exposes a document's score
   *
   * <p>If this source is used as part of a values calculation, then callers must not pass {@code
   * null} as the {@link DoubleValues} parameter on {@link #getValues(LeafReaderContext,
   * DoubleValues)}
   */
  public static final DoubleValuesSource SCORES =
      new DoubleValuesSource() {
        @Override
        public DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores)
            throws IOException {
          assert scores != null;
          return scores;
        }

        @Override
        public boolean needsScores() {
          return true;
        }

        @Override
        public boolean isCacheable(LeafReaderContext ctx) {
          return false;
        }

        @Override
        public Explanation explain(LeafReaderContext ctx, int docId, Explanation scoreExplanation) {
          return scoreExplanation;
        }

        @Override
        public int hashCode() {
          return 0;
        }

        @Override
        public boolean equals(Object obj) {
          return obj == this;
        }

        @Override
        public String toString() {
          return "scores";
        }

        @Override
        public DoubleValuesSource rewrite(IndexSearcher searcher) {
          return this;
        }
      };

  /** Creates a DoubleValuesSource that always returns a constant value */
  public static DoubleValuesSource constant(double value) {
    return new ConstantValuesSource(value);
  }

  private static class ConstantValuesSource extends DoubleValuesSource {

    private final DoubleValues doubleValues;
    private final double value;

    private ConstantValuesSource(double value) {
      this.value = value;
      this.doubleValues =
          new DoubleValues() {
            @Override
            public double doubleValue() {
              return value;
            }

            @Override
            public boolean advanceExact(int doc) {
              return true;
            }
          };
    }

    @Override
    public DoubleValuesSource rewrite(IndexSearcher searcher) {
      return this;
    }

    @Override
    public DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
      return doubleValues;
    }

    @Override
    public boolean needsScores() {
      return false;
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      return true;
    }

    @Override
    public Explanation explain(LeafReaderContext ctx, int docId, Explanation scoreExplanation) {
      return Explanation.match(value, "constant(" + value + ")");
    }

    @Override
    public int hashCode() {
      return Objects.hash(value);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ConstantValuesSource that = (ConstantValuesSource) o;
      return Double.compare(that.value, value) == 0;
    }

    @Override
    public String toString() {
      return "constant(" + value + ")";
    }
  }

  /**
   * Returns a DoubleValues instance that wraps scores returned by a Scorer.
   *
   * <p>Note: If you intend to call {@link Scorable#score()} on the provided {@code scorer}
   * separately, you may want to consider wrapping the collector with {@link
   * ScoreCachingWrappingScorer#wrap(LeafCollector)} to avoid computing the actual score multiple
   * times.
   */
  public static DoubleValues fromScorer(Scorable scorer) {
    return new DoubleValues() {
      @Override
      public double doubleValue() throws IOException {
        return scorer.score();
      }

      @Override
      public boolean advanceExact(int doc) throws IOException {
        return true;
      }
    };
  }

  private static class FieldValuesSource extends DoubleValuesSource {

    final String field;
    final LongToDoubleFunction decoder;

    private FieldValuesSource(String field, LongToDoubleFunction decoder) {
      this.field = field;
      this.decoder = decoder;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      FieldValuesSource that = (FieldValuesSource) o;
      return Objects.equals(field, that.field) && Objects.equals(decoder, that.decoder);
    }

    @Override
    public String toString() {
      return "double(" + field + ")";
    }

    @Override
    public int hashCode() {
      return Objects.hash(field, decoder);
    }

    @Override
    public DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
      final NumericDocValues values = DocValues.getNumeric(ctx.reader(), field);
      return new DoubleValues() {
        @Override
        public double doubleValue() throws IOException {
          return decoder.applyAsDouble(values.longValue());
        }

        @Override
        public boolean advanceExact(int target) throws IOException {
          return values.advanceExact(target);
        }
      };
    }

    @Override
    public boolean needsScores() {
      return false;
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      return DocValues.isCacheable(ctx, field);
    }

    @Override
    public Explanation explain(LeafReaderContext ctx, int docId, Explanation scoreExplanation)
        throws IOException {
      DoubleValues values = getValues(ctx, null);
      if (values.advanceExact(docId))
        return Explanation.match(values.doubleValue(), this.toString());
      else return Explanation.noMatch(this.toString());
    }

    @Override
    public DoubleValuesSource rewrite(IndexSearcher searcher) throws IOException {
      return this;
    }
  }

  private static class DoubleValuesSortField extends SortField {

    final DoubleValuesSource producer;

    DoubleValuesSortField(DoubleValuesSource producer, boolean reverse) {
      super(producer.toString(), new DoubleValuesComparatorSource(producer), reverse);
      this.producer = producer;
    }

    @Override
    public void setMissingValue(Object missingValue) {
      if (missingValue instanceof Number) {
        this.missingValue = missingValue;
        ((DoubleValuesComparatorSource) getComparatorSource())
            .setMissingValue(((Number) missingValue).doubleValue());
      } else {
        super.setMissingValue(missingValue);
      }
    }

    @Override
    public boolean needsScores() {
      return producer.needsScores();
    }

    @Override
    public String toString() {
      StringBuilder buffer = new StringBuilder("<");
      buffer.append(getField()).append(">");
      if (reverse) buffer.append("!");
      return buffer.toString();
    }

    @Override
    public SortField rewrite(IndexSearcher searcher) throws IOException {
      DoubleValuesSource rewrittenSource = producer.rewrite(searcher);
      if (rewrittenSource == producer) {
        return this;
      }
      DoubleValuesSortField rewritten = new DoubleValuesSortField(rewrittenSource, reverse);
      if (missingValue != null) {
        rewritten.setMissingValue(missingValue);
      }
      return rewritten;
    }
  }

  private static class DoubleValuesHolder {
    DoubleValues values;
  }

  private static class DoubleValuesComparatorSource extends FieldComparatorSource {
    private final DoubleValuesSource producer;
    private double missingValue;

    DoubleValuesComparatorSource(DoubleValuesSource producer) {
      this.producer = producer;
      this.missingValue = 0d;
    }

    void setMissingValue(double missingValue) {
      this.missingValue = missingValue;
    }

    @Override
    public FieldComparator<Double> newComparator(
        String fieldname, int numHits, Pruning pruning, boolean reversed) {
      return new DoubleComparator(numHits, fieldname, missingValue, reversed, Pruning.NONE) {
        @Override
        public LeafFieldComparator getLeafComparator(LeafReaderContext context) throws IOException {
          DoubleValuesHolder holder = new DoubleValuesHolder();

          return new DoubleComparator.DoubleLeafComparator(context) {
            LeafReaderContext ctx;

            @Override
            protected NumericDocValues getNumericDocValues(
                LeafReaderContext context, String field) {
              ctx = context;
              return asNumericDocValues(holder, Double::doubleToLongBits);
            }

            @Override
            public void setScorer(Scorable scorer) throws IOException {
              holder.values = producer.getValues(ctx, fromScorer(scorer));
              super.setScorer(scorer);
            }
          };
        }
      };
    }
  }

  private static NumericDocValues asNumericDocValues(
      DoubleValuesHolder in, DoubleToLongFunction converter) {
    return new NumericDocValues() {
      @Override
      public long longValue() throws IOException {
        return converter.applyAsLong(in.values.doubleValue());
      }

      @Override
      public boolean advanceExact(int target) throws IOException {
        return in.values.advanceExact(target);
      }

      @Override
      public int docID() {
        throw new UnsupportedOperationException();
      }

      @Override
      public int nextDoc() throws IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public int advance(int target) throws IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public long cost() {
        throw new UnsupportedOperationException();
      }
    };
  }

  /** Create a DoubleValuesSource that returns the score of a particular query */
  public static DoubleValuesSource fromQuery(Query query) {
    return new QueryDoubleValuesSource(query);
  }

  private static class QueryDoubleValuesSource extends DoubleValuesSource {

    private final Query query;

    private QueryDoubleValuesSource(Query query) {
      this.query = query;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      QueryDoubleValuesSource that = (QueryDoubleValuesSource) o;
      return Objects.equals(query, that.query);
    }

    @Override
    public int hashCode() {
      return Objects.hash(query);
    }

    @Override
    public DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
      throw new UnsupportedOperationException("This DoubleValuesSource must be rewritten");
    }

    @Override
    public boolean needsScores() {
      return false;
    }

    @Override
    public DoubleValuesSource rewrite(IndexSearcher searcher) throws IOException {
      return new WeightDoubleValuesSource(
          searcher.rewrite(query).createWeight(searcher, ScoreMode.COMPLETE, 1f));
    }

    @Override
    public String toString() {
      return "score(" + query.toString() + ")";
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      return false;
    }
  }

  private static class WeightDoubleValuesSource extends DoubleValuesSource {

    private final Weight weight;

    private WeightDoubleValuesSource(Weight weight) {
      this.weight = weight;
    }

    @Override
    public DoubleValues getValues(LeafReaderContext ctx, DoubleValues scores) throws IOException {
      Scorer scorer = weight.scorer(ctx);
      if (scorer == null) return DoubleValues.EMPTY;

      return new DoubleValues() {
        private final TwoPhaseIterator tpi = scorer.twoPhaseIterator();
        private final DocIdSetIterator disi =
            (tpi == null) ? scorer.iterator() : tpi.approximation();
        private Boolean tpiMatch = null; // cache tpi.matches()

        @Override
        public double doubleValue() throws IOException {
          return scorer.score();
        }

        @Override
        public boolean advanceExact(int doc) throws IOException {
          if (disi.docID() < doc) {
            disi.advance(doc);
            tpiMatch = null;
          }
          if (disi.docID() == doc) {
            if (tpi == null) {
              return true;
            } else if (tpiMatch == null) {
              tpiMatch = tpi.matches();
            }
            return tpiMatch;
          }
          return false;
        }
      };
    }

    @Override
    public Explanation explain(LeafReaderContext ctx, int docId, Explanation scoreExplanation)
        throws IOException {
      return weight.explain(ctx, docId);
    }

    @Override
    public boolean needsScores() {
      return false;
    }

    @Override
    public DoubleValuesSource rewrite(IndexSearcher searcher) throws IOException {
      return this;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      WeightDoubleValuesSource that = (WeightDoubleValuesSource) o;
      return Objects.equals(weight, that.weight);
    }

    @Override
    public int hashCode() {
      return Objects.hash(weight);
    }

    @Override
    public String toString() {
      return "score(" + weight.parentQuery.toString() + ")";
    }

    @Override
    public boolean isCacheable(LeafReaderContext ctx) {
      return false;
    }
  }
}
