package com.example.perf

fun main() {
    val test = QiitaArticleExperimentsBenchmarkTest()
    test.experiment1_PairVsOutParamVsPackedValueClass()
    test.experiment2_CollectionVsSequenceVsLoop()
    test.experiment3_VarargSpreadVsArrayPass()
    test.experiment4_SortingLargeVsTiny()
    test.experiment5_CacheLocalityAndCounterproductiveMemoization()
}
