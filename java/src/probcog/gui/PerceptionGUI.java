package probcog.gui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;

import javax.swing.*;
import edu.wpi.rail.jrosbridge.*;

import java.text.*;
import java.util.*;
import java.util.Timer;

import april.config.*;
import april.jmat.*;
import april.jmat.geom.*;
import april.sim.*;
import april.util.*;
import april.vis.*;
import april.vis.VisCameraManager.CameraPosition;
//x import probcog.arm.*;
import probcog.classify.*;
import probcog.classify.Features.FeatureCategory;
import probcog.rosie.perception.CategorizedData.CategoryType;
import probcog.perception.*;
import probcog.perception.Tracker.TrackerSettings;
import probcog.sensor.*;
import probcog.sim.SimLocation;
import probcog.sim.SimObjectPC;
import probcog.sim.SoarConcepts;
import probcog.util.*;
import probcog.vis.*;

import edu.wpi.rail.jrosbridge.*;
import edu.wpi.rail.jrosbridge.messages.*;
import edu.wpi.rail.jrosbridge.callback.*;
import javax.json.*;

public class PerceptionGUI extends JFrame
{
    //x private ArmStatus arm;
    //x private ArmController controller;
    private Tracker tracker;
    private ProbCogSimulator simulator;

    private Timer sendObservationTimer;
    private static final int OBSERVATION_RATE = 10; // # sent per second

    // Periodic tasks
    PeriodicTasks tasks = new PeriodicTasks(2);

    // Vis Stuff
    VisWorld vw;
    VisLayer vl;
    VisCanvas vc;

    // GUI Stuff
    JMenuBar menuBar;
    JMenu controlMenu, editMenu;
    JMenuItem clearData, reloadData;
    JMenuItem undoAction, redoAction;

    // GUI State
    Object selectionLock = new Object();
    int selectedId = -1;
    SelectionAnimation animation = null;

    enum ViewType {
    	POINT_CLOUD, SOAR
    };
    enum ClickType {
    	SELECT, CHANGE_ID, VISIBLE
    };

    ViewType viewType;
    ClickType clickType;
    Boolean showSoarObjects;
    Boolean showSegmentedObjects;

    Boolean drawPerceptionObjects = true;
    Boolean drawBeliefObjects = true;
    Boolean drawPropertyLabels = true;
    Boolean drawPointClouds = true;

    long soarTime = 0;

    private Ros ros;
    private Topic observations;

    public PerceptionGUI(GetOpt opts) throws IOException
    {
        super("ProbCog");
        this.setSize(800, 600);
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setLayout(new BorderLayout());

        vw = new VisWorld();
        vl = new VisLayer(vw);
        vc = new VisCanvas(vl);
        this.add(vc, BorderLayout.CENTER);

	// Default camera values taken from experimenting---sorry
	vl.cameraManager.uiLookAt(new double[]{ 2.05196, -0.87788, 1.75393},
				  new double[]{ 0.15207,  0.37279, 0.0},
				  new double[]{-0.51005,  0.33576, 0.79191},
				  true);

	// Customized event handling
	vl.addEventHandler(new BoltEventHandler());

        // Handle Options
        Config config = new ConfigFile(opts.getString("config"));

    	// Initialize the simulator
        simulator = new ProbCogSimulator(opts, vw, vl, vc);

        // Initialize object tracker
        Tracker.TrackerSettings trackerSettings;
        if(opts.getBoolean("kinect")){
        	// Use a real kinect and full perception pipeline
        	trackerSettings = new Tracker.TrackerSettings(true, true, true);
        } else if(opts.getInt("simquality") == 0){
        	// Poor sim quality, no simulated kinect, point clouds, or segmentation
        	trackerSettings = new Tracker.TrackerSettings(false, false, false);
        } else if(opts.getInt("simquality") == 1){
        	// Ok sim quality, simulate kinect and point clouds, but no segmentation
        	trackerSettings = new Tracker.TrackerSettings(false, false, true);
        } else {
        	// Best sim quality, simulate kinect, point clouds, and full segmentation
        	trackerSettings = new Tracker.TrackerSettings(false, true, true);
        }
        tracker = new Tracker(config, trackerSettings, simulator.getWorld());
        if (opts.getString("backup") != null) {
            System.out.println("ATTN: Loading from autosave file");
            tracker.loadBackup(opts.getString("backup"));
            System.out.println("ATTN: Successfully restored from autosave file");
        }

        // Replace arm stuff next
        //x if (opts.getBoolean("arm")) {
        //     ArmDriver driver = new ArmDriver(config);
        //     (new Thread(driver)).start();
        // } else {
        //     SimArm simArm = new SimArm(config, simulator.getWorld());
        //}

        ros = new Ros();
        ros.connect();

        if (ros.isConnected()) {
            System.out.println("PerceptionGUI connected to rosbridge server.");
        }
        else {
            System.out.println("PerceptionGUI NOT CONNECTED TO ROSBRIDGE");
        }

        observations = new Topic(ros,
                                 "/rosie_observations",
                                 "rosie_msgs/Observations",
                                 500);

        //x Not sure if this is a mission-critical feature
        // subscribe("GUI_COMMAND", this);

        Topic trains = new Topic(ros,
                                 "rosie_training",
                                 "rosie_msgs/TrainingData",
                                 500);
        System.out.println("Perception subscribed to training labels!");
        trains.subscribe(new TopicCallback() {
                public void handleMessage(Message message) {
                    JsonObject jobj = message.toJsonObject();
                    JsonArray arr = jobj.getJsonArray("labels");
                    ArrayList<Long> times = new ArrayList<Long>();
                    for(int i=0; i < arr.size(); i++){
                        JsonObject tl = arr.getJsonObject(i);
                        long t = tl.getJsonNumber("utime").longValue();
                        times.add(t);
                        if(t <= soarTime){
                            // already seen this label, don't train a second time
                            continue;
                        }

                        Obj objTrain;
                        synchronized(tracker.stateLock){
                            objTrain = tracker.getObject(tl.getInt("id"));
                        }
                        if(objTrain != null){
                            FeatureCategory cat =
                                Features.getFeatureCategory(CategoryType.values()[tl.getInt("cat")]);
                            ArrayList<Double> features = objTrain.getFeatures(cat);
                            if(features != null){
                                tracker.addTraining(cat, features,
                                                    tl.getString("label"));
                            }
                        }
                    }
                    for(Long t : times) {
                        soarTime = Math.max(soarTime, t);
                    }
                }
            });

        // Initialize the JMenuBar
        createMenuBar();
        addToMenu(menuBar); // XXX Ew
        this.setJMenuBar(menuBar);

        // Set GUI modes
        viewType = ViewType.POINT_CLOUD;
        clickType = ClickType.SELECT;
        showSoarObjects = false;
        showSegmentedObjects = false;

        this.setVisible(true);
        class SendObservationTask extends TimerTask{
			public void run() {
        		sendMessage();
			}
        }
        sendObservationTimer = new Timer();
        sendObservationTimer.schedule(new SendObservationTask(),
                                      1000,
                                      1000/OBSERVATION_RATE);

        // Render updates about the world
        RenderThread rt = new RenderThread();

        // Write to file task
        tasks.addFixedDelay(new AutoSaveTask(), 10.0);
        tasks.addFixedDelay(new MenuUpdateTask(), 0.2);
        tasks.setRunning(true);
        rt.start();
    }


    public void createMenuBar()
    {
    	menuBar = new JMenuBar();
        controlMenu = new JMenu("Control");

        menuBar.add(controlMenu);

        // Remove all data (no built in info)
        clearData = new JMenuItem("Clear All Data");
        clearData.addActionListener(new ActionListener(){
                @Override
                public void actionPerformed(ActionEvent e) {
                    System.out.println("CLEARED DATA");
                    tracker.clearClassificationData();
                }
            });
        controlMenu.add(clearData);

        // Remove all data (including training)
        reloadData = new JMenuItem("Reload Data");
        reloadData.addActionListener(new ActionListener(){
                @Override
                public void actionPerformed(ActionEvent e) {
                    tracker.reloadClassificationData();
                }
            });
        controlMenu.add(reloadData);

        // Edit menu XXX
        editMenu = new JMenu("Edit");
        menuBar.add(editMenu);

        // Undo & redo actions
        undoAction = new JMenuItem("Undo");
        undoAction.addActionListener(new ActionListener()
            {
                public void actionPerformed(ActionEvent e) {
                    tracker.undoClassification();
                }
            });
        editMenu.add(undoAction);
        redoAction = new JMenuItem("Redo");
        redoAction.addActionListener(new ActionListener()
            {
                public void actionPerformed(ActionEvent e) {
                    tracker.undoClassification();
                }
            });
        editMenu.add(redoAction);
    }

    public void resetSimObjects(){
        synchronized(simulator.world) {
        	for(SimObject obj : simulator.world.objects){
        		if(obj instanceof SimObjectPC){
        			SimObjectPC simObj = (SimObjectPC)obj;
        			if(simObj.getState(SoarConcepts.DOOR) != null){
        				simObj.setState(SoarConcepts.DOOR, SoarConcepts.CLOSED);
        			}
        			if(simObj.getState(SoarConcepts.HEAT) != null){
        				simObj.setState(SoarConcepts.HEAT, SoarConcepts.OFF);
        			}
        			if(simObj.getState(SoarConcepts.MEAT) != null){
        				simObj.setState(SoarConcepts.MEAT, SoarConcepts.RAW);
        			}
        		}
        	}
        }
    }

    public void sendMessage()
    {
        double[] planeCoeff = tracker.getFloorPlane();
        if (planeCoeff == null) return;

        int cid = -1;
        synchronized(tracker.stateLock){
            cid = getSelectedId();
        }

        JsonArrayBuilder eyejab = Json.createArrayBuilder();
        JsonArrayBuilder lookjab = Json.createArrayBuilder();
        JsonArrayBuilder upjab = Json.createArrayBuilder();

        ArrayList<Sensor> sensors = tracker.getSensors();
        if(sensors.size() == 0){
            eyejab = eyejab.add(0.6).add(0.0).add(1.0);
            lookjab = lookjab.add(0.0).add(0.0).add(0.0);
            upjab = upjab.add(-1.0).add(0.0).add(1.0);

        } else {
            Sensor s = sensors.get(0);
            CameraPosition camera = Util.getSensorPos(s);
            eyejab = eyejab
                .add(camera.eye[0])
                .add(camera.eye[1])
                .add(camera.eye[2]);
            lookjab = lookjab
                .add(camera.lookat[0])
                .add(camera.lookat[1])
                .add(camera.lookat[2]);
            upjab = upjab
                .add(camera.up[0])
                .add(camera.up[1])
                .add(camera.up[2]);
        }

        JsonArrayBuilder tjab = Json.createArrayBuilder();
        tjab = tjab
            .add(planeCoeff[0])
            .add(planeCoeff[1])
            .add(planeCoeff[2])
            .add(planeCoeff[3]);

        JsonObject jo = Json.createObjectBuilder()
            .add("click_id", cid)
            .add("soar_utime", soarTime)
            .add("eye", eyejab)
            .add("lookat", lookjab)
            .add("up", upjab)
            .add("table", tjab)
            .add("observations", tracker.getObjectData())
            .add("nobs", tracker.getNumObj()).build();

        Message m = new Message(jo);
        observations.publish(m);
    }

    /** AutoSave the classifier state */
    class AutoSaveTask implements PeriodicTasks.Task
    {
        String filename;

        public AutoSaveTask()
        {
            Date date = new Date(System.currentTimeMillis());
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy_MM_dd-HH_mm_ss");
            filename = "/tmp/probcog_autosave_"+sdf.format(date);
        }

        public void run(double dt)
        {
            try {
                tracker.writeClassificationState(filename);
            } catch (IOException ioex)  {
                System.err.println("ERR: Could not save to autosave file");
                ioex.printStackTrace();
            }
        }
    }

    /** Update the status of menu entries to ensure only the
     *  appropriate ones are marked as active
     */
    class MenuUpdateTask implements PeriodicTasks.Task
    {
        public void run(double dt)
        {
            if (tracker.canUndoClassification()) {
                undoAction.setEnabled(true);
            } else {
                undoAction.setEnabled(false);
            }

            if (tracker.canRedoClassification()) {
                redoAction.setEnabled(true);
            } else {
                redoAction.setEnabled(false);
            }
        }
    }

    // XXX BEGIN CODE DUMP
    // =====================================================
    public int getSelectedId()
    {
    	return selectedId;
    }


    public void addToMenu(JMenuBar menuBar)
    {
    	JMenu simMenu = new JMenu("Simulator");

    	addViewTypeMenu(simMenu);
    	simMenu.addSeparator();
    	addClickTypeMenu(simMenu);
        menuBar.add(simMenu);
    }

    private void addClickTypeMenu(JMenu menu)
    {
    	menu.add(new JLabel("Change Selection Mode"));
    	ButtonGroup clickGroup = new ButtonGroup();

    	JRadioButtonMenuItem select = new JRadioButtonMenuItem("Select Object");
    	select.addActionListener(new ActionListener(){
			@Override
			public void actionPerformed(ActionEvent arg0) {
				setClickType(ClickType.SELECT);
			}
    	});
    	clickGroup.add(select);
    	menu.add(select);

    	JRadioButtonMenuItem visiblity = new JRadioButtonMenuItem("Toggle Visibility");
    	visiblity.addActionListener(new ActionListener(){
			@Override
			public void actionPerformed(ActionEvent arg0) {
				setClickType(ClickType.VISIBLE);
			}
    	});
    	clickGroup.add(visiblity);
    	menu.add(visiblity);


    	JRadioButtonMenuItem changeId = new JRadioButtonMenuItem("Change Id");
    	changeId.addActionListener(new ActionListener(){
			@Override
			public void actionPerformed(ActionEvent arg0) {
				setClickType(ClickType.CHANGE_ID);
			}
    	});
    	clickGroup.add(changeId);
    	menu.add(changeId);
    }

    private void addViewTypeMenu(JMenu menu)
    {
    	menu.add(new JLabel("Change Simulator View"));

    	JCheckBox percObjs = new JCheckBox("Perception Objects");
    	percObjs.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                if(e.getStateChange() == ItemEvent.DESELECTED) {
                	drawPerceptionObjects = false;
                	vw.getBuffer("perc-objects").swap();
                }
                else if(e.getStateChange() == ItemEvent.SELECTED) {
                	drawPerceptionObjects = true;
                }
            }
        });
    	percObjs.setSelected(true);
    	menu.add(percObjs);

    	JCheckBox propLabels = new JCheckBox("Property Labels");
    	propLabels.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                if(e.getStateChange() == ItemEvent.DESELECTED) {
                	drawPropertyLabels = false;
                	vw.getBuffer("perc-labels").swap();
                }
                else if(e.getStateChange() == ItemEvent.SELECTED) {
                	drawPropertyLabels = true;
                }
            }
        });
    	propLabels.setSelected(true);
    	menu.add(propLabels);

    	JCheckBox beliefObjs = new JCheckBox("Belief Objects");
    	beliefObjs.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                if(e.getStateChange() == ItemEvent.DESELECTED) {
                	drawBeliefObjects = false;
                	vw.getBuffer("belief-objects").swap();
                	vw.getBuffer("belief-labels").swap();
                }
                else if(e.getStateChange() == ItemEvent.SELECTED) {
                	drawBeliefObjects = true;
                }
            }
        });
    	beliefObjs.setSelected(true);
    	menu.add(beliefObjs);

    	JCheckBox pointClouds = new JCheckBox("Point Clouds");
    	pointClouds.addItemListener(new ItemListener() {
            public void itemStateChanged(ItemEvent e) {
                if(e.getStateChange() == ItemEvent.DESELECTED) {
                	drawPointClouds = false;
                	vw.getBuffer("object-view").swap();
                }
                else if(e.getStateChange() == ItemEvent.SELECTED) {
                	drawPointClouds = true;
                }
            }
        });
    	pointClouds.setSelected(true);
    	menu.add(pointClouds);
    }

    private void setView(ViewType view)
    {
        System.out.println("Set view: "+view);
    	this.viewType = view;
    }

    private void setClickType(ClickType click)
    {
    	this.clickType = click;
    }

    class BoltEventHandler extends VisEventAdapter
    {
        public int getDispatchOrder()
        {
            return -20; // Want to beat out the sim, though pass events through
        }

        /** Keep track of the last object to be selected in the sim world. */
        public boolean mousePressed(VisCanvas vc, VisLayer vl, VisCanvas.RenderInfo rinfo, GRay3D ray, MouseEvent e)
        {
            double bestd = Double.MAX_VALUE;
            Object selectedObject = null;

            synchronized(simulator.world) {
                synchronized(tracker.stateLock) {
                	for (Obj ob : tracker.getWorldState().values()) {
                        double d = Collisions.collisionDistance(ray.getSource(), ray.getDir(), ob.getShape(), ob.getPoseMatrix());
                        if (d < bestd) {
                            synchronized (selectionLock) {
                            	selectedObject = ob;
                                bestd = d;
                            }
                        }
                    }
                }

                // XXX -- DIE SENSABLES - might want to reconsider later
                // if(bestd == Double.MAX_VALUE){
                // 	HashMap<Integer, SimSensable> sensables = SensableManager.getSingleton().getSensables();
	            //     synchronized(sensables){
	            //     	for (SimSensable sens : sensables.values()) {
	            //     		if(!(sens instanceof SimObject)){
	            //     			continue;
	            //     		}
	            //     		SimObject obj = (SimObject)sens;
	            //             double d = Collisions.collisionDistance(ray.getSource(), ray.getDir(), obj.getShape(), obj.getPose());

	            //             if (d < bestd) {
	            //                 synchronized (selectionLock) {
	            //                 	selectedObject = obj;
	            //                     bestd = d;
	            //                 }
	            //             }
	            //         }
	            //     }
                // }

                if(selectedObject != null){
                	if(selectedObject instanceof Obj){
                		Obj ob = (Obj)selectedObject;
                		switch(clickType){
                		case CHANGE_ID:
                			if(ob.getSourceSimObject() != null && ob.getSourceSimObject() instanceof SimObjectPC){
                        		SimObjectPC simObj = ((SimObjectPC)ob.getSourceSimObject());
                        		simObj.setID(Obj.nextID());
                        	}
                			break;
                        case SELECT:
                        	animation = null;
                        	selectedId = ob.getID();
                        	break;
                        case VISIBLE:
                        	if(ob.getSourceSimObject() != null && ob.getSourceSimObject() instanceof SimObjectPC){
                        		SimObjectPC simObj = ((SimObjectPC)ob.getSourceSimObject());
                        		simObj.setVisible(!simObj.getVisible());
                        	}
                        	break;
                        }
                	}
                    // XXX -- DIE SENSABLES - might want to reconsider later
                    // else if(selectedObject instanceof SimSensable){
                	// 	SimSensable ob = (SimSensable)selectedObject;
                	// 	switch(clickType){
                    //     case SELECT:
                    //     	animation = null;
                    //     	selectedId = ob.getID();
                    //     	break;
                    //     }
                	// }
                }
            }

            return false;
        }
    }

    /** Render ProbCog-specific content. */
    class RenderThread extends Thread
    {
        int fps = 20;

        public void run()
        {
            Tic tic = new Tic();
            while (true) {
                double dt = tic.toctic();

                // x If anyone else is ever going to use this again...
                // TODO FIX IT
                drawFPS();
                //if(drawPointClouds){
                drawPointCloud();

                    //} else {
                	//drawObjectBoxes();
                    //}

                drawSelection(dt);
                drawTablePlane();
                // if(drawPerceptionObjects){
                drawPerceptionObjects();
                //}
                double[][] faceCamera = calcFaceCameraMatrix();
                // if(drawBeliefObjects){
                drawBeliefObjects();
                // }
                // if(drawBeliefObjects){
                drawBeliefLabels(faceCamera);
                // }
                // if(drawPropertyLabels){
                drawPerceptionLabels(faceCamera);
                // }

                drawSensors();

                //x arm.render(vw);

                TimeUtil.sleep(1000/fps);

            }
        }
    }

    public double[][] calcFaceCameraMatrix(){
        // === XXX THE BELOW TRIES TO RENDER TEXT OVER OBJECTS ===
    	CameraPosition camera = vl.cameraManager.getCameraTarget();
		double[] forward = LinAlg.normalize(LinAlg.subtract(camera.eye, camera.lookat));
		// Spherical coordinates
        double psi = Math.PI/2.0 - Math.asin(forward[2]);   // psi = Pi/2 - asin(z)
        double theta = Math.atan2(forward[1], forward[0]);  // theta = atan(y/x)
        if(forward[0] == 0 && forward[1] == 0){
        	theta = -Math.PI/2;
        }
        double[][] tilt = LinAlg.rotateX(psi); 				// tilt up or down to face the camera vertically
        double[][] rot = LinAlg.rotateZ(theta + Math.PI/2); // rotate flat to face the camera horizontally
        double[][] faceCamera = LinAlg.matrixAB(rot, tilt);
        return faceCamera;
    }

    public void drawFPS(){
        // Render sensor pipeline FPS.
        Formatter f = new Formatter();
        f.format("<<monospaced-128>>SENSOR FPS: %3.2f\n", tracker.fps);
        VzText fpsText = new VzText(VzText.ANCHOR.TOP_LEFT,
                                    f.toString());
        VisChain fpsChain = new VisChain(LinAlg.scale(0.1),
                                         fpsText);
        VisPixCoords sensorFPS = new VisPixCoords(VisPixCoords.ORIGIN.TOP_LEFT,
                                                  fpsChain);
        VisWorld.Buffer fpsBuffer = vw.getBuffer("sensor-fps");
        fpsBuffer.addBack(sensorFPS);
        fpsBuffer.swap();
    }

    private void drawObjectBoxes(){
    	VisWorld.Buffer buffer = vw.getBuffer("object-view");
    	synchronized(tracker.stateLock){
			for(Obj ob : tracker.getWorldState().values()){
            	if(isDrawablePerceptionObject(ob)){
            		buffer.addBack(ob.getVisObject());
            	}
	    	}
    	}
    	buffer.swap();
    }

    private void drawTablePlane() {
        VisWorld.Buffer vb = vw.getBuffer("table");
        double[] planeCoeff = tracker.getFloorPlane();

        if (planeCoeff == null) return;

        double mX = 0.8;
        double mY = 0.0;

        double mZ = (planeCoeff[3] + planeCoeff[0]*mX +
                     planeCoeff[1]*mY) / -planeCoeff[2];

        double[] n = new double[]{planeCoeff[0], planeCoeff[1], planeCoeff[2]};
        double[] up = new double[]{0, 0, 1};

        double[] rpy = LinAlg.quatToRollPitchYaw(LinAlg.quatCompute(up,
                                                                    LinAlg.normalize(n)));

        double[] xyzrpy = new double[]{mX, mY, mZ-0.01, rpy[0], rpy[1], rpy[2]};

        double[][] trans = LinAlg.xyzrpyToMatrix(xyzrpy);
        VzBox box = new VzBox(1, 1, 0.02, new VzMesh.Style(new Color(0,0,0,0)), new VzLines.Style(Color.darkGray, 1));
        vb.addBack(new VisChain(trans, box));
        vb.swap();
    }

    public void drawSensors()
    {
        VisWorld.Buffer vb = vw.getBuffer("kinect");
        ArrayList<Sensor> sensors = tracker.getSensors();
        for (Sensor s: sensors) {
            vb.addBack(new VisChain(s.getCameraXform(),
                                    LinAlg.scale(0.1),
                                    new VzAxes()));
            //CameraPosition camera = Util.getSensorPos(s);
            //Util.printCamera(camera);
        }

        vb.swap();
    }

	private void drawPointCloud()
    {
    	VisWorld.Buffer buffer = vw.getBuffer("object-view");
    	synchronized(tracker.stateLock){
            ArrayList<double[]> points = tracker.getPointCloud();

            if (points == null) return;
                //System.out.printf("Drawing object %d with %d pts\n", ob.getID(), points.size());
            if(points != null && points.size() > 0){
                VisColorData colors = new VisColorData();
                VisVertexData vertexData = new VisVertexData();
                for(double[] pt : points){
                    vertexData.add(new double[]{pt[0], pt[1], pt[2]});
                    colors.add(new Color((int)pt[3]).getRGB());
                }
                VzPoints visPts = new VzPoints(vertexData, new VzPoints.Style(colors, 2));
                VisChain pointsChain = new VisChain(visPts);
                buffer.addBack(pointsChain);
            }
        }
    	buffer.swap();
	}

    private void drawSelection(double dt){
    	synchronized(tracker.stateLock){
            HashMap<Integer, Obj> worldState = tracker.getWorldState();
	    	if(worldState.containsKey(selectedId)){
	    		if(animation == null){
	        		Obj selectedObject = worldState.get(selectedId);
	                double[] xyz = LinAlg.resize(LinAlg.matrixToXyzrpy(selectedObject.getPoseMatrix()), 3);
                    double br = Math.abs(selectedObject.getShape().getBoundingRadius());
	                //animation = new SelectionAnimation(xyz, br*1.2);
	                animation = new SelectionAnimation(selectedObject);
	    		}
	    	} else {
	    		animation = null;
	    	}
            if (animation != null) {
                VisWorld.Buffer vb = vw.getBuffer("selection");
                animation.step(dt);
                vb.addBack(animation);
                vb.swap();
            } else {
            	vw.getBuffer("selection").clear();
            }
    	}
    }

	private Boolean isDrawablePerceptionObject(Obj obj){
    	if(obj.isVisible() == false){
    		return false;
    	}
    	if(obj.getSourceSimObject() != null && obj.getSourceSimObject() instanceof SimObjectPC){
    		SimObjectPC simObj = (SimObjectPC)obj.getSourceSimObject();
    		if(simObj instanceof SimLocation || simObj.getVisible() == false){
    			return false;
    		}
    	}
		return true;
	}

    private void drawPerceptionObjects(){
		VisWorld.Buffer buffer = vw.getBuffer("perc-objects");
    	synchronized(tracker.stateLock){
            HashMap<Integer, Obj> worldState = tracker.getWorldState();
            for(Obj obj : worldState.values()){
            	if(!isDrawablePerceptionObject(obj)){
            		continue;
            	}

            	// Draw box outline
                BoundingBox bbox = obj.getBoundingBox();
               // System.out.println(String.format("%d H: %+.5f   Z: %+.5f", obj.getID(), bbox.lenxyz[2], bbox.xyzrpy[2]));
    			double[] s = bbox.lenxyz;
    			double[][] scale = LinAlg.scale(s[0], s[1], s[2]);
    			double[][] trans = LinAlg.xyzrpyToMatrix(bbox.xyzrpy);
    			VzBox box = new VzBox(new VzMesh.Style(new Color(0,0,0,0)), new VzLines.Style(Color.blue, 2));
    			buffer.addBack(new VisChain(trans, scale, box));
            }
    	}
    	buffer.swap();
    }

    private void drawPerceptionLabels(double[][] faceCamera){
		VisWorld.Buffer buffer = vw.getBuffer("perc-labels");
    	synchronized(tracker.stateLock){
            HashMap<Integer, Obj> worldState = tracker.getWorldState();
            for(Obj obj : worldState.values()){
            	if(!isDrawablePerceptionObject(obj)){
            		continue;
            	}

            	String labelString = "";
        		String tf="<<monospaced,blue,dropshadow=false>>";
        		labelString += String.format("%s%d\n", tf, obj.getID());

        		if(drawPropertyLabels){
            		for(FeatureCategory cat : FeatureCategory.values()){
                        if (cat == FeatureCategory.LOCATION)
                            continue;   // These don't matter
                        Classifications cs = obj.getLabels(cat);
                        Classifications.Label bestLabel = cs.getBestLabel();
                        if(cat == FeatureCategory.TEMPERATURE || cat == FeatureCategory.WEIGHT){
                        	ArrayList<Double> features = obj.getFeatures(cat);
                        	if(features != null){
                                labelString += String.format("%s%s:%.3f\n", tf, 
                                           (cat == FeatureCategory.WEIGHT ? "weight" : "temp" ), obj.getFeatures(cat).get(0));
                        	}
                        }
//                        if(cat == FeatureCategory.COLOR){
//                        	labelString += String.format("%sColor = %.3f\n", tf, obj.getFeatures(cat).get(3));
//                        }
                        if(bestLabel.label.equals("unknown")){
                        	continue;
                        }
                        if(bestLabel != null && cat != FeatureCategory.LOCATION){
                    		labelString += String.format("%s%s:%.2f\n", tf,
                                    bestLabel.label,
                                    bestLabel.weight);
                        }
            		}
        		}

        		VzText text = new VzText(labelString);
        		double[] textLoc = new double[]{obj.getPose()[0], obj.getPose()[1], obj.getPose()[2] + .1};
                buffer.addBack(new VisChain(LinAlg.translate(textLoc), faceCamera, LinAlg.scale(.002), text));
            }
    	}
        buffer.swap();
    }

    private void drawBeliefObjects(){
    	VisWorld.Buffer buffer = vw.getBuffer("belief-objects");
    	HashMap<Integer, Obj> soarObjects;
    	synchronized(tracker.stateLock){
    		soarObjects = tracker.getSoarObjects();
    		for(Obj obj : soarObjects.values()){
    			BoundingBox bbox = obj.getBoundingBox();
    			double[] s = bbox.lenxyz;
    			double[][] scale = LinAlg.scale(s[0], s[1], s[2]);
    			double[][] trans = LinAlg.xyzrpyToMatrix(bbox.xyzrpy);
    			VzBox box = new VzBox(new VzMesh.Style(new Color(0,0,0,0)), new VzLines.Style(Color.black, 4));
    			buffer.addBack(new VisChain(trans, scale, box));
    		}
    	}
    	buffer.swap();
    }

    private void drawBeliefLabels(double[][] faceCamera){
    	VisWorld.Buffer buffer = vw.getBuffer("belief-labels");
    	HashMap<Integer, Obj> soarObjects;
    	synchronized(tracker.stateLock){
    		soarObjects = tracker.getSoarObjects();
    		for(Obj obj : soarObjects.values()){
    			BoundingBox bbox = obj.getBoundingBox();
        		String tf="<<monospaced,black,dropshadow=#FFFFFFFF>>";
        		String labelString = String.format("%s%d\n", tf, obj.getID());
        		VzText text = new VzText(labelString);
        		double[] textLoc = new double[]{bbox.xyzrpy[0], bbox.xyzrpy[1], bbox.xyzrpy[2]};
        		double[] corner = new double[]{bbox.lenxyz[0]/2, bbox.lenxyz[1]/2, bbox.lenxyz[2]/2, 1};
        		corner = LinAlg.matrixAB(LinAlg.xyzrpyToMatrix(bbox.xyzrpy), corner);
        		buffer.addBack(new VisChain(LinAlg.translate(corner), faceCamera, LinAlg.scale(.002), text));
    		}
    	}
    	buffer.swap();
    }

    public static void main(String args[])
    {
        GetOpt opts = new GetOpt();

        opts.addBoolean('h', "help", false, "Show this help screen");
        opts.addString('c', "config", null, "Global configuration file");
        opts.addString('w', "world", null, "Simulated world file");
        //opts.addBoolean('a', "arm", false, "Run with a phsyical arm");
        opts.addBoolean('k', "kinect", false, "Use a physical kinect");
        opts.addBoolean('d', "debug", false, "Toggle debugging mode");
        opts.addInt('s', "simquality", 2, "2 = full simulation, 1 = no segmentation, 0 = no sim kinect");
        opts.addString('\0', "backup", null, "Load from backup file");

        if (!opts.parse(args)) {
            System.err.println("ERR: Error parsing args - "+opts.getReason());
            System.exit(1);
        }
        if (opts.getBoolean("help")) {
            opts.doHelp();
            System.exit(0);
        }

        // Spin up the GUI
        try {
            PerceptionGUI gui = new PerceptionGUI(opts);
        } catch (IOException ioex) {
            System.err.println("ERR: Error starting GUI");
            ioex.printStackTrace();
            System.exit(1);
        }
    }
}
