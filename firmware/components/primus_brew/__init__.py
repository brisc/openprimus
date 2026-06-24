"""
primus_brew — custom ESPHome component for the openPrimus brew controller.

This is a STUB scaffold. It currently only defines the YAML configuration
schema so `primus.yaml` validates. The real-time brew control loop (pressure
PID + VPS flow profiling, boiler fill) is implemented in C++ next.

See docs/OPEN_SOURCE_ESPHOME_ARCHITECTURE.md §5 for the design.
"""
import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.cpp_generator import RawExpression
from esphome import pins

# Namespace + classes for the generated C++ (defined later in brew_control.h/cpp)
CONF_PRIMUS_BREW = "primus_brew"
primus_brew_ns = cg.esphome_ns.namespace("primus_brew")
PrimusBrew = primus_brew_ns.class_("PrimusBrew", cg.Component)

# Config keys
CONF_PUMP = "pump"
CONF_PRESSURE_SENSOR = "pressure_sensor"
CONF_PADDLE_SENSOR = "paddle_sensor"

# Brew modes (mirror the stock state machine — docs/PRIMUS_FIRMWARE_INTERNALS.md §7)
MODES = {
    "CLASSIC_9BAR": "CLASSIC_9BAR",
    "NINE_BAR_PREINFUSION": "NINE_BAR_PREINFUSION",
    "SEMI_MANUAL_VPS": "SEMI_MANUAL_VPS",
    "MANUAL_VPS": "MANUAL_VPS",
    "MANUAL_FLOW": "MANUAL_FLOW",
    "PROFILE_COFFEE": "PROFILE_COFFEE",
    "DRIP_COFFEE": "DRIP_COFFEE",
}

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(): cv.declare_id(PrimusBrew),
        cv.Optional(CONF_PUMP): cv.use_id(None),
        cv.Optional(CONF_PRESSURE_SENSOR): cv.use_id(None),
        cv.Optional(CONF_PADDLE_SENSOR): cv.use_id(None),
    }
).extend(cv.COMPONENT_SCHEMA)


async def to_code(config):
    """Generate C++ setup. Stubbed — wires up the component with its dependencies."""
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    if CONF_PUMP in config:
        pump = await cg.get_variable(config[CONF_PUMP])
        cg.add(var.set_pump(pump))
    if CONF_PRESSURE_SENSOR in config:
        psens = await cg.get_variable(config[CONF_PRESSURE_SENSOR])
        cg.add(var.set_pressure_sensor(psens))
    if CONF_PADDLE_SENSOR in config:
        psens = await cg.get_variable(config[CONF_PADDLE_SENSOR])
        cg.add(var.set_paddle_sensor(psens))
