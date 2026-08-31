from lyrebird_groundstation.mavlink_helpers import (
    climb_rate_mps,
    gps_fix_type,
    ground_speed_mps,
    heartbeat_base_mode,
    is_armed_flight_mode,
)


def test_is_armed_flight_mode_treats_idle_modes_as_disarmed():
    assert not is_armed_flight_mode(None)
    assert not is_armed_flight_mode("")
    assert not is_armed_flight_mode("UNKNOWN")
    assert not is_armed_flight_mode("MANUAL")
    assert is_armed_flight_mode("GPS")
    assert is_armed_flight_mode("VIRTUAL_STICK")


def test_heartbeat_base_mode_combines_safety_and_gps_flags():
    assert (
        heartbeat_base_mode(
            armed=True,
            satellite_count=7,
            custom_mode_enabled_flag=0b0001,
            safety_armed_flag=0b0010,
            stabilize_enabled_flag=0b0100,
            guided_enabled_flag=0b1000,
        )
        == 0b1111
    )


def test_heartbeat_base_mode_keeps_gps_flags_off_when_satellite_count_is_low():
    assert (
        heartbeat_base_mode(
            armed=False,
            satellite_count=5,
            custom_mode_enabled_flag=0b0001,
            safety_armed_flag=0b0010,
            stabilize_enabled_flag=0b0100,
            guided_enabled_flag=0b1000,
        )
        == 0b0001
    )


def test_gps_fix_type_tracks_satellite_thresholds():
    assert gps_fix_type(0) == 0
    assert gps_fix_type(3) == 0
    assert gps_fix_type(4) == 2
    assert gps_fix_type(5) == 2
    assert gps_fix_type(6) == 3


def test_ground_speed_mps_uses_horizontal_components():
    assert ground_speed_mps({"x": 3, "y": 4, "z": 12}) == 5.0
    assert ground_speed_mps({}) == 0.0


def test_climb_rate_mps_uses_mavlink_positive_climb_convention():
    assert climb_rate_mps({"z": -1.5}) == 1.5
    assert climb_rate_mps({"z": 2.0}) == -2.0
    assert climb_rate_mps({}) == 0.0
