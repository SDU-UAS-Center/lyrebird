"""The two-wire comparison behind the MAVLink tab, exercised against synthetic bursts.

The comparison is the instrument that found every MAVLink defect in this project, so how it
treats sampling skew is load-bearing. Each wire caches its latest frame and updates at its own
message rate, so a single read is skewed by however far the aircraft moved between the two
updates -- which is exactly how the gimbal rows read "differ" while the aircraft was moving,
with both wires carrying the same value sampled a few milliseconds apart. A burst de-skews by
requiring the wires to agree on *any* pair: a real disagreement survives every pair, while
sampling skew does not.
"""

from mavlink_debug import _rows


def _burst(http_values, mavlink_values, key="gimbalJointAttitude"):
    """One burst where both wires report ``key`` with the given series of values."""
    pairs = []
    for http, mavlink in zip(http_values, mavlink_values, strict=True):
        pairs.append(({key: http}, {key: mavlink}))
    return pairs


def _status_for(http_values, mavlink_values, key="gimbalJointAttitude"):
    rows = _rows(_burst(http_values, mavlink_values, key))
    return next(row["status"] for row in rows if row["key"] == key)


def test_identical_values_agree():
    assert _status_for([1.0, 1.0, 1.0], [1.0, 1.0, 1.0]) == "agree"


def test_sampling_skew_does_not_flag_differ():
    """Both wires carry the same ramping signal, read a step apart.

    Every same-instant pair differs by 2 (beyond the 1.0 tolerance), but the burst finds the
    cross pairs where the two caches hold the same underlying value, so the field agrees. A
    single read would have flagged "differ" purely from timing.
    """
    http = [0.0, 3.0, 6.0]
    mavlink = [2.0, 5.0, 8.0]
    assert _status_for(http, mavlink) == "agree"


def test_a_real_disagreement_survives_the_burst():
    # A constant offset on every pair -- and on every cross pair -- is a wire disagreement.
    assert _status_for([10.0, 10.0, 10.0], [20.0, 20.0, 20.0]) == "differ"


def test_a_dict_field_with_skewed_yaw_agrees():
    """The gimbal row: pitch/roll identical, yaw skewed the way two non-simultaneous reads are."""
    http = [
        {"pitch": 1.7, "roll": 1.9, "yaw": 0.0},
        {"pitch": 1.7, "roll": 1.9, "yaw": 3.0},
        {"pitch": 1.7, "roll": 1.9, "yaw": 6.0},
    ]
    mavlink = [
        {"pitch": 1.7, "roll": 1.9, "yaw": 2.0},
        {"pitch": 1.7, "roll": 1.9, "yaw": 5.0},
        {"pitch": 1.7, "roll": 1.9, "yaw": 8.0},
    ]
    assert _status_for(http, mavlink) == "agree"


def test_a_dict_field_with_a_real_yaw_offset_diffs():
    http = [
        {"pitch": 1.7, "roll": 1.9, "yaw": 10.0},
        {"pitch": 1.7, "roll": 1.9, "yaw": 10.0},
        {"pitch": 1.7, "roll": 1.9, "yaw": 10.0},
    ]
    mavlink = [
        {"pitch": 1.7, "roll": 1.9, "yaw": 40.0},
        {"pitch": 1.7, "roll": 1.9, "yaw": 40.0},
        {"pitch": 1.7, "roll": 1.9, "yaw": 40.0},
    ]
    assert _status_for(http, mavlink) == "differ"


def test_fields_on_one_wire_are_reported_not_compared():
    pairs = [({"onlyOnHttp": 1}, {})]
    rows = _rows(pairs)
    assert next(r["status"] for r in rows if r["key"] == "onlyOnHttp") == "httpOnly"

    pairs = [({}, {"onlyOnMavlink": 1})]
    rows = _rows(pairs)
    assert next(r["status"] for r in rows if r["key"] == "onlyOnMavlink") == "mavlinkOnly"


def test_a_redundant_http_twin_does_not_read_as_http_only():
    """HTTP reports battery under two names; MAVLink uses one.

    The value is the same 36% on both wires, so the HTTP-only twin must fold into the shared
    batteryLevel row rather than surface as a field MAVLink is missing.
    """
    pairs = [
        ({"batteryLevel": 36, "remainingCharge": 36}, {"batteryLevel": 36}),
    ]
    rows = _rows(pairs)
    battery = next(row for row in rows if row["key"] == "batteryLevel")
    assert battery["status"] == "agree"
    assert all(row["key"] != "remainingCharge" for row in rows)


def test_disagreements_sort_first():
    pairs = [
        (
            {"yawReached": True, "gimbalJointAttitude": {"yaw": 1.0}},
            {"yawReached": True, "gimbalJointAttitude": {"yaw": 20.0}},
        ),
        (
            {"yawReached": True, "gimbalJointAttitude": {"yaw": 1.0}},
            {"yawReached": True, "gimbalJointAttitude": {"yaw": 20.0}},
        ),
    ]
    rows = _rows(pairs)
    assert [row["status"] for row in rows] == ["differ", "agree"]
