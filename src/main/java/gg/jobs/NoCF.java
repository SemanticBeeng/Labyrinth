package gg.jobs;

import gg.BagOperatorHost;
import gg.CFLConfig;
import gg.ElementOrEvent;
import gg.KickoffSource;
import gg.LabyNode;
import gg.LabySource;
import gg.operators.AssertBagEquals;
import gg.operators.Bagify;
import gg.operators.IdMap;
import gg.partitioners.Always0;
import gg.partitioners.RoundRobin;
import gg.util.Nothing;
import gg.util.Util;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.DiscardingSink;

import java.util.Arrays;

public class NoCF {

	private static TypeSerializer<String> stringSer = TypeInformation.of(String.class).createSerializer(new ExecutionConfig());

	public static void main(String[] args) throws Exception {
		StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

//		Configuration cfg = new Configuration();
//		cfg.setLong("taskmanager.network.numberOfBuffers", 16384);
//		StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(100, cfg);

		//env.getConfig().setParallelism(1);

		CFLConfig.getInstance().terminalBBId = 0;
		KickoffSource kickoffSrc = new KickoffSource(0);
		env.addSource(kickoffSrc).addSink(new DiscardingSink<>());
        final int para = env.getParallelism();

		String[] words = new String[]{"alma", "korte", "alma", "b", "b", "b", "c", "d", "d"};


//		DataStream<ElementOrEvent<String>> input =
//				env.fromCollection(Arrays.asList(words))
//						.transform("bagify", Util.tpe(), new Bagify<>(new RoundRobin<>(para), 0))
//                        .setConnectionType(new gg.partitioners.FlinkPartitioner<>());

		LabySource<String> input = new LabySource<>(env.fromCollection(Arrays.asList(words)), 0);

		//System.out.println(input.getParallelism());

//		DataStream<ElementOrEvent<String>> output = input
//				//.setConnectionType(new gg.partitioners.Forward<>())
//				.bt("id-map",input.getType(),
//				new BagOperatorHost<String, String>(new IdMap<>(), 0, 1, stringSer)
//						.addInput(0, 0, true, 0)
//						.out(0,0,true, new gg.partitioners.Always0<>(1)))
//				.setConnectionType(new gg.partitioners.FlinkPartitioner<>());

		LabyNode<String, String> output = new LabyNode<>("id-map", new IdMap<>(), 0, new RoundRobin<>(para), stringSer);

//		output
//				//.setConnectionType(new gg.partitioners.Forward<>())
//				.bt("assert", Util.tpe(), new BagOperatorHost<>(new AssertBagEquals<>("alma", "korte", "alma", "b", "b", "b", "c", "d", "d"), 0, 2, stringSer)
//						.addInput(0, 0, true, 1))
//				.setParallelism(1);

		LabyNode<String, Nothing> sink =
				new LabyNode<String, Nothing>(
						"assert",
						new AssertBagEquals<>("alma", "korte", "alma", "b", "b", "b", "c", "d", "d"),
						0,
						new Always0<String>(1), stringSer);

		CFLConfig.getInstance().setNumToSubscribe();

		env.execute();
	}
}
