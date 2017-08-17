package gg.jobs;

import gg.CFLConfig;
import gg.KickoffSource;
import gg.LabyNode;
import gg.LabySource;
import gg.operators.ClickLogReader;
import gg.operators.ConditionNode;
import gg.operators.FlatMap;
import gg.operators.GroupBy0Sum1TupleIntInt;
import gg.operators.IncMap;
import gg.operators.JoinTupleIntInt;
import gg.operators.Print;
import gg.operators.SingletonBagOperator;
import gg.operators.SmallerThan;
import gg.operators.Sum;
import gg.operators.SumCombiner;
import gg.partitioners.Always0;
import gg.partitioners.Forward;
import gg.partitioners.TupleIntIntBy0;
import gg.util.TupleIntInt;
import gg.util.Unit;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.io.TupleCsvInputFormat;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;

import java.util.Collections;

public class ClickCountDiffs {

    private static TupleTypeInfo<Tuple2<Integer, Integer>> typeInfoTupleIntInt = new TupleTypeInfo<>(TypeInformation.of(Integer.class), TypeInformation.of(Integer.class));

    private static TypeSerializer<Integer> integerSer = TypeInformation.of(Integer.class).createSerializer(new ExecutionConfig());
    private static TypeSerializer<Boolean> booleanSer = TypeInformation.of(Boolean.class).createSerializer(new ExecutionConfig());
    private static TypeSerializer<TupleIntInt> tupleIntIntSer = new TupleIntInt.TupleIntIntSerializer();

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        //env.setParallelism(1);

        final String pref = args[0] + "/";


        CFLConfig.getInstance().terminalBBId = 4;
        KickoffSource kickoffSrc = new KickoffSource(0, 1);
        env.addSource(kickoffSrc).addSink(new DiscardingSink<>());

        final int para = env.getParallelism();


        // BB 0

        DataStream<TupleIntInt> pageAttributesStream = env.createInput(new TupleCsvInputFormat<Tuple2<Integer, Integer>>(
                new Path(pref + "in/pageAttributes.tsv"),"\n", "\t", typeInfoTupleIntInt), typeInfoTupleIntInt)
                .map(new MapFunction<Tuple2<Integer, Integer>, TupleIntInt>() {
            @Override
            public TupleIntInt map(Tuple2<Integer, Integer> value) throws Exception {
                return TupleIntInt.of(value.f0, value.f1);
            }
        });

        LabySource<TupleIntInt> pageAttributes =
                new LabySource<>(pageAttributesStream, 0);

        @SuppressWarnings("unchecked")
        LabySource<TupleIntInt> yesterdayCounts_1 =
                new LabySource<>(env.fromCollection(Collections.emptyList(), TypeInformation.of(TupleIntInt.class)), 0);

        LabySource<Integer> day_1 =
                new LabySource<>(env.fromCollection(Collections.singletonList(1)), 0)
                        .setParallelism(1);

        // -- Iteration starts here --   BB 1

        LabyNode<TupleIntInt, TupleIntInt> yesterdayCounts_2 =
                LabyNode.phi("yesterdayCounts_2", 1, new Forward<>(para), tupleIntIntSer)
                .addInput(yesterdayCounts_1, false);

        LabyNode<Integer, Integer> day_2 =
                LabyNode.phi("day_2", 1, new Always0<>(1), integerSer)
                .addInput(day_1, false)
                .setParallelism(1);

        LabyNode<Integer, Integer> visits_1 =
                new LabyNode<>("visits_1", new ClickLogReader(pref + "in/clickLog_"), 1, new Always0<>(1), integerSer)
                        .addInput(day_2, true, false)
                        .setParallelism(1);

        // The inputs of the join have to be the same type (because of the union stuff), so we add a dummy tuple element.
        LabyNode<Integer, TupleIntInt> visits_1_tupleized =
                new LabyNode<>("visits_1_tupleized", new FlatMap<Integer, TupleIntInt>() {
                    @Override
                    public void pushInElement(Integer e, int logicalInputId) {
                        super.pushInElement(e, logicalInputId);
                        out.collectElement(TupleIntInt.of(e, -1));
                    }
                }, 1, new Forward<>(para), integerSer)
                .addInput(visits_1, true, false);

        LabyNode<TupleIntInt, TupleIntInt> joinedWithAttrs =
                new LabyNode<>("joinedWithAttrs", new JoinTupleIntInt() {
                    @Override
                    protected void udf(int b, TupleIntInt p) {
                        out.collectElement(TupleIntInt.of(p.f0, b));
                    }
                }, 1, new TupleIntIntBy0(para), tupleIntIntSer)
                .addInput(pageAttributes, false)
                .addInput(visits_1_tupleized, true, false);

        LabyNode<TupleIntInt, TupleIntInt> visits_2 =
                new LabyNode<>("visits_2", new FlatMap<TupleIntInt, TupleIntInt>() {
                    @Override
                    public void pushInElement(TupleIntInt e, int logicalInputId) {
                        super.pushInElement(e, logicalInputId);
                        if (e.f1 == 0) {
                            out.collectElement(e);
                        }
                    }
                }, 1, new Forward<>(para), tupleIntIntSer)
                .addInput(joinedWithAttrs, true, false);

        LabyNode<TupleIntInt, TupleIntInt> clicksMapped =
                new LabyNode<>("clicksMapped", new FlatMap<TupleIntInt, TupleIntInt>() {
                    @Override
                    public void pushInElement(TupleIntInt e, int logicalInputId) {
                        super.pushInElement(e, logicalInputId);
                        out.collectElement(TupleIntInt.of(e.f0, 1));
                    }
                }, 1, new Forward<>(para), tupleIntIntSer)
                .addInput(visits_2, true, false);

        LabyNode<TupleIntInt, TupleIntInt> counts =
                new LabyNode<>("counts", new GroupBy0Sum1TupleIntInt(), 1, new TupleIntIntBy0(para), tupleIntIntSer)
                .addInput(clicksMapped, true, false);

        LabyNode<Integer, Boolean> notFirstDay =
                new LabyNode<>("notFirstDay", new SingletonBagOperator<Integer, Boolean>() {
                    @Override
                    public void pushInElement(Integer e, int logicalInputId) {
                        super.pushInElement(e, logicalInputId);
                        out.collectElement(!e.equals(1));
                    }
                }, 1, new Always0<>(1), integerSer)
                .addInput(day_2, true, false)
                .setParallelism(1);

        LabyNode<Boolean, Unit> ifCond =
                new LabyNode<>("ifCond", new ConditionNode(new int[]{2,3}, new int[]{3}), 1, new Always0<>(1), booleanSer)
                .addInput(notFirstDay, true, false)
                .setParallelism(1);

        // -- then branch   BB 2

        // The join of joinedYesterday is merged into this operator
        LabyNode<TupleIntInt, TupleIntInt> diffs =
                new LabyNode<>("diffs", new JoinTupleIntInt() {
                    @Override
                    protected void udf(int b, TupleIntInt p) {
                        out.collectElement(TupleIntInt.of(p.f0, Math.abs(b - p.f1)));
                    }
                }, 2, new TupleIntIntBy0(para), tupleIntIntSer)
                .addInput(yesterdayCounts_2, false, true)
                .addInput(counts, false, true);

        LabyNode<TupleIntInt, Integer> diffsInt =
                new LabyNode<>("diffsInt", new FlatMap<TupleIntInt, Integer>() {
                    @Override
                    public void pushInElement(TupleIntInt e, int logicalInputId) {
                        super.pushInElement(e, logicalInputId);
                        out.collectElement(e.f1);
                    }
                }, 2, new Forward<>(para), tupleIntIntSer)
                .addInput(diffs, true, false);

        LabyNode<Integer, Integer> sumCombiner =
                new LabyNode<>("sumCombiner", new SumCombiner(), 2, new Forward<>(para), integerSer)
                .addInput(diffsInt, true, false);

        LabyNode<Integer, Integer> sum =
                new LabyNode<>("sum", new Sum(), 2, new Always0<>(1), integerSer)
                .addInput(sumCombiner, true, false)
                .setParallelism(1);

        LabyNode<Integer, Unit> printSum =
                new LabyNode<>("printSum", new Print<>("printSum"), 2, new Always0<>(1), integerSer)
                .addInput(sum, true, false)
                .setParallelism(1);

        // -- end of then branch   BB 3

        // (We "optimize away" yesterdayCounts_3, since it would be an IdMap)
        yesterdayCounts_2.addInput(counts, false, true);

        LabyNode<Integer, Integer> day_3 =
                new LabyNode<>("day_3", new IncMap(), 3, new Always0<>(1), integerSer)
                .addInput(day_2, false, false)
                .setParallelism(1);

        day_2.addInput(day_3, false, true);

        LabyNode<Integer, Boolean> notLastDay =
                new LabyNode<>("notLastDay", new SmallerThan(Integer.parseInt(args[1]) + 1), 3, new Always0<>(1), integerSer)
                .addInput(day_3, true, false)
                .setParallelism(1);

        LabyNode<Boolean, Unit> exitCond =
                new LabyNode<>("exitCond", new ConditionNode(1, 4), 3, new Always0<>(1), booleanSer)
                .addInput(notLastDay, true, false)
                .setParallelism(1);

        // -- Iteration ends here   BB 4

        // Itt nincs semmi operator. (A kiirast a BB 2-ben csinaljuk.)

        LabyNode.translateAll();

        env.execute();
    }
}