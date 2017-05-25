package beam.sim

import beam.agentsim.config.{ConfigModule}
import beam.sim.modules.{AgentsimModule, BeamAgentModule}
import beam.agentsim.controler.corelisteners.BeamControllerCoreListenersModule
import beam.agentsim.controler.BeamControler
import beam.agentsim.utils.FileUtils
import org.matsim.core.controler._
import org.matsim.core.mobsim.qsim.QSim
import org.matsim.core.scenario.{ScenarioByInstanceModule, ScenarioUtils}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

object RunBeam extends App{

  // Inject and use tsConfig instead here
  // Make implicit to be able to pass as implicit arg to constructors requiring config (no need for explicit imports).
  FileUtils.setConfigOutputFile(ConfigModule.beamConfig.beam.outputs.outputDirectory, ConfigModule.beamConfig.beam.agentsim.simulationName, ConfigModule.matSimConfig)
  val injector: com.google.inject.Injector =
    org.matsim.core.controler.Injector.createInjector(ConfigModule.matSimConfig, AbstractModule.`override`(ListBuffer(new AbstractModule() {
      override def install(): Unit = {
        // MATSim defaults
        val scenario = ScenarioUtils.loadScenario(ConfigModule.matSimConfig)
        install(new NewControlerModule)
        install(new ScenarioByInstanceModule(scenario))
        install(new ControlerDefaultsModule)
        install(new BeamControllerCoreListenersModule)

        // Beam Inject below:
        install(new ConfigModule)
        install(new AgentsimModule)
        install(new BeamAgentModule)
      }
    }).asJava, new AbstractModule() {
      override def install(): Unit = {

        // Beam -> MATSim Wirings

        bindMobsim().to(classOf[QSim]) //TODO: This will change
        addControlerListenerBinding().to(classOf[BeamSim])
        bind(classOf[ControlerI]).to(classOf[BeamControler]).asEagerSingleton()
      }
    }))

  val services: BeamServices = injector.getInstance(classOf[BeamServices])
  services.controler.run()
}
