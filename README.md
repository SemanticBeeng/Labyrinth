# Labyrinth

This repository contains the code of Labyrinth, our representation of general control flow in Flink dataflows. It is an SSA-like representation, where each assignment corresponds to a Flink operator.

For example, if we have the following pseudocode:

```
i = 1
do {
    i = i + 1
} while (i < 100)
assert i == 100
```

This translates to the following:

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

int n = 100;

CFLConfig.getInstance().terminalBBId = 2;
KickoffSource kickoffSrc = new KickoffSource(0,1);
env.addSource(kickoffSrc).addSink(new DiscardingSink<>());

TypeSerializer<Integer> integerSer = TypeInformation.of(Integer.class).createSerializer(new ExecutionConfig());
TypeSerializer<Boolean> booleanSer = TypeInformation.of(Boolean.class).createSerializer(new ExecutionConfig());

Integer[] input = new Integer[]{1};

LabySource<Integer> inputBag = new LabySource<>(env.fromCollection(Arrays.asList(input)), 0, TypeInformation.of(new TypeHint<ElementOrEvent<Integer>>(){}));

LabyNode<Integer, Integer> phi =
		LabyNode.phi("phi", 1, new Random<>(env.getParallelism()), integerSer, TypeInformation.of(new TypeHint<ElementOrEvent<Integer>>(){}))
				.addInput(inputBag, false);

LabyNode<Integer, Integer> inced =
		new LabyNode<>("inc-map", new IncMap(), 1, new Random<>(env.getParallelism()), integerSer, TypeInformation.of(new TypeHint<ElementOrEvent<Integer>>(){}))
				.addInput(phi, true, false);

phi.addInput(inced, false, true);

LabyNode<Integer, Boolean> smallerThan =
		new LabyNode<>("smaller-than", new SmallerThan(n), 1, new Always0<>(1), integerSer, TypeInformation.of(new TypeHint<ElementOrEvent<Boolean>>(){}))
				.addInput(inced, true, false)
				.setParallelism(1);

LabyNode<Boolean, Unit> exitCond =
		new LabyNode<>("exit-cond", new ConditionNode(1,2), 1, new Always0<>(1), booleanSer, TypeInformation.of(new TypeHint<ElementOrEvent<Unit>>(){}))
				.addInput(smallerThan, true, false)
				.setParallelism(1);

LabyNode<Integer, Unit> assertEquals =
		new LabyNode<>("Check i == " + n, new AssertEquals<>(n), 2, new Always0<>(1), integerSer, TypeInformation.of(new TypeHint<ElementOrEvent<Unit>>(){}))
			.addInput(inced, false, true)
			.setParallelism(1);


LabyNode.translateAll();

env.execute();
```
