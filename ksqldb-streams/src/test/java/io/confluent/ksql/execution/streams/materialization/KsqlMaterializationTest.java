/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.execution.streams.materialization;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Range;
import com.google.common.collect.Streams;
import com.google.common.testing.NullPointerTester;
import com.google.common.testing.NullPointerTester.Visibility;
import io.confluent.ksql.GenericKey;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.Window;
import io.confluent.ksql.execution.streams.materialization.KsqlMaterialization.KsqlMaterializedTable;
import io.confluent.ksql.execution.streams.materialization.KsqlMaterialization.KsqlMaterializedWindowedTable;
import io.confluent.ksql.execution.streams.materialization.KsqlMaterialization.Transform;
import io.confluent.ksql.model.WindowType;
import io.confluent.ksql.name.ColumnName;
import io.confluent.ksql.schema.ksql.LogicalSchema;
import io.confluent.ksql.schema.ksql.types.SqlTypes;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.SessionWindow;
import org.apache.kafka.streams.kstream.internals.TimeWindow;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import org.mockito.InOrder;
import org.mockito.Mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KsqlMaterializationTest {

  private LogicalSchema schema;
  private GenericKey aKey;
  private GenericKey aKey2;
  private long aRowtime;
  private Range<Instant> windowStartBounds;
  private Range<Instant> windowEndBounds;
  private int partition;
  private GenericRow aValue;
  private GenericRow aValue2;
  private GenericRow transformed;
  private Window aWindow;
  private TimeWindow streamWindow;
  private Row row;
  private Row row2;
  private WindowedRow windowedRow;
  private WindowedRow windowedRow2;


  @Mock
  private Materialization inner;
  @Mock
  private Transform project;
  @Mock
  private Transform filter;
  @Mock
  private MaterializedTable innerNonWindowed;
  @Mock
  private MaterializedWindowedTable innerWindowed;

  private KsqlMaterialization materialization;

  @Before
  public void setUp() {
    schema = LogicalSchema.builder()
            .keyColumn(ColumnName.of("k0"), SqlTypes.STRING)
            .valueColumn(ColumnName.of("v0"), SqlTypes.STRING)
            .valueColumn(ColumnName.of("v1"), SqlTypes.STRING)
            .build();

    aKey = GenericKey.genericKey("k");
    aKey2 = GenericKey.genericKey("k2");

    aRowtime = 12335L;

    windowStartBounds = Range.closed(
            Instant.now(),
            Instant.now().plusSeconds(10)
    );

    windowEndBounds = Range.closed(
            Instant.now().plusSeconds(1),
            Instant.now().plusSeconds(11)
    );
    partition = 0;

    aValue = GenericRow.genericRow("a", "b");
    aValue2 = GenericRow.genericRow("a2", "b2");
    transformed = GenericRow.genericRow("x", "y");
    aWindow = Window.of(Instant.now(), Instant.now().plusMillis(10));
    streamWindow = new TimeWindow(
            aWindow.start().toEpochMilli(),
            aWindow.end().toEpochMilli()
    );

    row = Row.of(
            schema,
            aKey,
            aValue,
            aRowtime
    );

    row2 = Row.of(
            schema,
            aKey2,
            aValue2,
            aRowtime
    );

    windowedRow = WindowedRow.of(
            schema,
            new Windowed<>(aKey, streamWindow),
            aValue,
            aRowtime
    );

    windowedRow2 = WindowedRow.of(
            schema,
            new Windowed<>(aKey2, streamWindow),
            aValue2,
            aRowtime
    );

    materialization = new KsqlMaterialization(
        inner,
            schema,
        ImmutableList.of(filter, project)
    );

    when(inner.nonWindowed()).thenReturn(innerNonWindowed);
    when(inner.windowed()).thenReturn(innerWindowed);

    when(innerNonWindowed.get(any(), anyInt())).thenReturn(Optional.of(row));
    when(innerNonWindowed.get(anyInt())).thenReturn(Iterators.forArray(row, row2));
    when(innerWindowed.get(any(), anyInt(), any(), any())).thenReturn(ImmutableList.of(windowedRow));
    when(innerWindowed.get(anyInt(), any(), any()))
        .thenReturn(ImmutableList.of(windowedRow, windowedRow2).iterator());
  }

  @SuppressWarnings("UnstableApiUsage")
  @Test
  public void shouldThrowNPEs() {
    new NullPointerTester()
        .setDefault(Materialization.class, inner)
        .setDefault(LogicalSchema.class, schema)
        .testConstructors(KsqlMaterialization.class, Visibility.PACKAGE);
  }

  @Test
  public void shouldReturnInnerLocator() {
    // Given:
    final Locator expected = mock(Locator.class);
    when(inner.locator()).thenReturn(expected);
    givenNoopTransforms();

    // When:
    final Locator locator = materialization.locator();

    // Then:
    assertThat(locator, is(sameInstance(expected)));
  }

  @Test
  public void shouldReturnInnerWindowType() {
    // Given:
    when(inner.windowType()).thenReturn(Optional.of(WindowType.SESSION));
    givenNoopTransforms();

    // When:
    final Optional<WindowType> windowType = materialization.windowType();

    // Then:
    assertThat(windowType, is(Optional.of(WindowType.SESSION)));
  }

  @Test
  public void shouldWrappedNonWindowed() {
    // Given:
    givenNoopTransforms();

    // When:
    final MaterializedTable table = materialization.nonWindowed();

    // Then:
    assertThat(table, is(instanceOf(KsqlMaterializedTable.class)));
  }

  @Test
  public void shouldWrappedWindowed() {
    // Given:
    givenNoopTransforms();

    // When:
    final MaterializedWindowedTable table = materialization.windowed();

    // Then:
    assertThat(table, is(instanceOf(KsqlMaterializedWindowedTable.class)));
  }

  @Test
  public void shouldCallInnerNonWindowedWithCorrectParamsOnGet() {
    // Given:
    final MaterializedTable table = materialization.nonWindowed();
    givenNoopFilter();
    when(project.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    table.get(aKey, partition);

    // Then:
    verify(innerNonWindowed).get(aKey, partition);
  }

  @Test
  public void shouldCallInnerWindowedWithCorrectParamsOnGet() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    givenNoopFilter();
    when(project.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    table.get(aKey, partition, windowStartBounds, windowEndBounds);

    // Then:
    verify(innerWindowed).get(aKey, partition, windowStartBounds, windowEndBounds);
  }

  @Test
  public void shouldCallFilterWithCorrectValuesOnNonWindowedGet() {
    // Given:
    final MaterializedTable table = materialization.nonWindowed();
    givenNoopFilter();
    when(project.apply(any(), any(), any())).thenReturn(Optional.of(transformed));


    // When:
    table.get(aKey, partition);

    // Then:
    verify(filter).apply(aKey, aValue, new PullProcessingContext(aRowtime));
  }

  @Test
  public void shouldCallFilterWithCorrectValuesOnWindowedGet() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    givenNoopFilter();
    when(project.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    table.get(aKey, partition, windowStartBounds, windowEndBounds);

    // Then:
    verify(filter).apply(
        new Windowed<>(aKey, streamWindow),
            aValue,
        new PullProcessingContext(aRowtime)
    );
  }

  @Test
  public void shouldReturnEmptyIfInnerNonWindowedReturnsEmpty() {
    // Given:
    final MaterializedTable table = materialization.nonWindowed();
    when(innerNonWindowed.get(any(), anyInt())).thenReturn(Optional.empty());
    givenNoopTransforms();

    // When:
    final Optional<?> result = table.get(aKey, partition);

    // Then:
    assertThat(result, is(Optional.empty()));
  }

  @Test
  public void shouldReturnEmptyIfInnerWindowedReturnsEmpty() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    when(innerWindowed.get(any(), anyInt(), any(), any())).thenReturn(ImmutableList.of());
    givenNoopTransforms();

    // When:
    final List<?> result = table.get(aKey, partition, windowStartBounds, windowEndBounds);

    // Then:
    assertThat(result, is(empty()));
  }

  @Test
  public void shouldFilterNonWindowed() {
    // Given:
    final MaterializedTable table = materialization.nonWindowed();
    givenNoopProject();
    when(filter.apply(any(), any(), any())).thenReturn(Optional.empty());

    // When:
    final Optional<?> result = table.get(aKey, partition);

    // Then:
    assertThat(result, is(Optional.empty()));
  }

  @Test
  public void shouldFilterWindowed() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    givenNoopProject();
    when(filter.apply(any(), any(), any())).thenReturn(Optional.empty());

    // When:
    final List<?> result = table.get(aKey, partition, windowStartBounds, windowEndBounds);

    // Then:
    assertThat(result, is(empty()));
  }

  @Test
  public void shouldFilterNonWindowed_fullScan() {
    // Given:
    final MaterializedTable table = materialization.nonWindowed();
    givenNoopProject();
    when(filter.apply(any(), any(), any())).thenReturn(Optional.empty());

    // When:
    final Iterator<?> result = table.get(partition);

    // Then:
    assertThat(result.hasNext(), is(false));
  }

  @Test
  public void shouldFilterWindowed_fullScan() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    givenNoopProject();
    when(filter.apply(any(), any(), any())).thenReturn(Optional.empty());

    // When:
    final Iterator<?> result = table.get(partition, windowStartBounds, windowEndBounds);

    // Then:
    assertThat(result.hasNext(), is(false));
  }

  @Test
  public void shouldCallTransformsInOrder() {
    // Given:
    final MaterializedTable table = materialization.nonWindowed();
    givenNoopFilter();
    when(project.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    table.get(aKey, partition);

    // Then:
    final InOrder inOrder = inOrder(project, filter);
    inOrder.verify(filter).apply(any(), any(), any());
    inOrder.verify(project).apply(any(), any(), any());
  }

  @Test
  public void shouldCallTransformsInOrderForWindowed() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    givenNoopFilter();
    when(project.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    table.get(aKey, partition, windowStartBounds, windowEndBounds);

    // Then:
    final InOrder inOrder = inOrder(project, filter);
    inOrder.verify(filter).apply(any(), any(), any());
    inOrder.verify(project).apply(any(), any(), any());
  }

  @Test
  public void shouldPipeTransforms() {
    // Given:
    final MaterializedTable table = materialization.nonWindowed();
    givenNoopProject();
    when(filter.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    table.get(aKey, partition);

    // Then:
    verify(project).apply(aKey, transformed, new PullProcessingContext(aRowtime));
  }

  @Test
  public void shouldPipeTransformsWindowed() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    givenNoopProject();
    when(filter.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    table.get(aKey, partition, windowStartBounds, windowEndBounds);

    // Then:
    verify(project).apply(
        new Windowed<>(aKey, streamWindow),
            transformed,
        new PullProcessingContext(aRowtime)
    );
  }

  @Test
  public void shouldPipeTransforms_fullTableScan() {
    // Given:
    final MaterializedTable table = materialization.nonWindowed();
    givenNoopProject();
    when(filter.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    Streams.stream(table.get(partition)).collect(Collectors.toList());


    // Then:
    verify(project).apply(aKey, transformed, new PullProcessingContext(aRowtime));
    verify(project).apply(aKey2, transformed, new PullProcessingContext(aRowtime));
  }

  @Test
  public void shouldPipeTransformsWindowed_fullTableScan() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    givenNoopProject();
    when(filter.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    Streams.stream(table.get(partition, windowStartBounds, windowEndBounds))
        .collect(Collectors.toList());

    // Then:
    verify(project).apply(
        new Windowed<>(aKey, streamWindow),
            transformed,
        new PullProcessingContext(aRowtime)
    );
    verify(project).apply(
        new Windowed<>(aKey2, streamWindow),
            transformed,
        new PullProcessingContext(aRowtime)
    );
  }

  @SuppressWarnings("OptionalGetWithoutIsPresent")
  @Test
  public void shouldReturnSelectTransformedFromNonWindowed() {
    // Given:
    final MaterializedTable table = materialization.nonWindowed();
    givenNoopFilter();
    when(project.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    final Optional<Row> result = table.get(aKey, partition);

    // Then:
    assertThat(result, is(not(Optional.empty())));
    assertThat(result.get().key(), is(aKey));
    assertThat(result.get().window(), is(Optional.empty()));
    assertThat(result.get().value(), is(transformed));
  }

  @Test
  public void shouldReturnSelectTransformedFromWindowed() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    givenNoopFilter();
    when(project.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    // When:
    final List<WindowedRow> result = table.get(aKey, partition, windowStartBounds,
            windowEndBounds);

    // Then:
    assertThat(result, hasSize(1));
    assertThat(result.get(0).key(), is(aKey));
    assertThat(result.get(0).window(), is(Optional.of(aWindow)));
    assertThat(result.get(0).value(), is(transformed));
  }

  @Test
  public void shouldMaintainResultOrdering() {
    // Given:
    final MaterializedWindowedTable table = materialization.windowed();
    givenNoopFilter();
    when(project.apply(any(), any(), any())).thenReturn(Optional.of(transformed));

    final Instant now = Instant.now();
    final TimeWindow window1 =
        new TimeWindow(now.plusMillis(10).toEpochMilli(), now.plusMillis(11).toEpochMilli());

    final SessionWindow window2 =
        new SessionWindow(now.toEpochMilli(), now.plusMillis(2).toEpochMilli());

    final TimeWindow window3 =
        new TimeWindow(now.toEpochMilli(), now.plusMillis(3).toEpochMilli());

    final ImmutableList<WindowedRow> rows = ImmutableList.of(
        WindowedRow.of(schema, new Windowed<>(aKey, window1), aValue, aRowtime),
        WindowedRow.of(schema, new Windowed<>(aKey, window2), aValue, aRowtime),
        WindowedRow.of(schema, new Windowed<>(aKey, window3), aValue, aRowtime)
    );

    when(innerWindowed.get(any(), anyInt(), any(), any())).thenReturn(rows);

    // When:
    final List<WindowedRow> result = table.get(aKey, partition, windowStartBounds,
            windowEndBounds);

    // Then:
    assertThat(result, hasSize(rows.size()));
    assertThat(result.get(0).windowedKey().window(), is(window1));
    assertThat(result.get(1).windowedKey().window(), is(window2));
    assertThat(result.get(2).windowedKey().window(), is(window3));
  }

  @Test
  public void shouldReturnIntermediateRowNonWindowed() {
    // Given:
    final GenericRow intermediateRow1 = aValue.append(aRowtime).append(aKey);
    final GenericRow intermediateRow2 = aValue2.append(aRowtime).append(aKey2);

    // When:
    final GenericRow genericRow1 = KsqlMaterialization.getIntermediateRow(row);
    final GenericRow genericRow2 = KsqlMaterialization.getIntermediateRow(row2);

    // Then:
    assertThat(genericRow1, is(intermediateRow1));
    assertThat(genericRow2, is(intermediateRow2));
  }

  @Test
  public void shouldReturnIntermediateRowWindowed() {
    // Given:
    final GenericRow intermediateRow1 = aValue
            .append(aRowtime)
            .append(aKey)
            .append(aWindow.start().toEpochMilli())
            .append(aWindow.end().toEpochMilli());

    final GenericRow intermediateRow2 = aValue2
            .append(aRowtime)
            .append(aKey2)
            .append(aWindow.start().toEpochMilli())
            .append(aWindow.end().toEpochMilli());

    // When:
    final GenericRow genericRow1 = KsqlMaterialization.getIntermediateRow(windowedRow);
    final GenericRow genericRow2 = KsqlMaterialization.getIntermediateRow(windowedRow2);

    // Then:
    assertThat(genericRow1, is(intermediateRow1));
    assertThat(genericRow2, is(intermediateRow2));
  }

  private void givenNoopFilter() {
    when(filter.apply(any(), any(), any()))
        .thenAnswer(inv -> Optional.of(inv.getArgument(1)));
  }

  private void givenNoopProject() {
    when(project.apply(any(), any(), any()))
        .thenAnswer(inv -> Optional.of(inv.getArgument(1)));
  }

  private void givenNoopTransforms() {
    givenNoopFilter();
    givenNoopProject();
  }
}