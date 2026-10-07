"""Second predeclared candidate on a fresh fixed case bank; never activates app routing."""

from scripts import eval_memory_route as evaluation
from scripts.memory_route_cases_v2 import VERSION, cases_for_split, split_statistics
from scripts.memory_route_consensus import ConsensusRouter


def experiment():
    return evaluation.Experiment(
        version=VERSION,
        case_reader=cases_for_split,
        stats_reader=split_statistics,
        sources=evaluation.SOURCE_PATHS
        + (
            "scripts/memory_route_consensus.py",
            "scripts/memory_route_cases_v2.py",
            "scripts/eval_memory_route_v2.py",
        ),
        router_type=ConsensusRouter,
        context_prototypes=False,
        strategy="third present similarity minus best retrieve; one prototype per train case; Q plus C veto",
    )


if __name__ == "__main__":
    evaluation.main(spec=experiment())
