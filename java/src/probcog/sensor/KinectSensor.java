package probcog.sensor;

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import javax.swing.*;
import java.util.*;
import java.nio.ByteBuffer;

//x import lcm.lcm.*;

import april.camera.*;
import april.camera.models.*;
import april.config.*;
import april.jmat.*;
import april.sim.SimObject;
import april.util.*;
import april.vis.*;

//x import probcog.lcmtypes.*;
import probcog.perception.PointCloud;
import probcog.sensor.SimKinectSensor.SimPixel;
import probcog.sim.SimLocation;
import probcog.util.Util;

import edu.wpi.rail.jrosbridge.*;
import edu.wpi.rail.jrosbridge.messages.*;
import edu.wpi.rail.jrosbridge.callback.*;
import javax.json.*;
import javax.xml.bind.DatatypeConverter;

/** Provides access to the frames taken by the kinect. Only
 *  keeps track of the most recently received frame from the
 *  kinect.
 **/
public class KinectSensor implements Sensor
{
    Config config;

    Object kinectLock = new Object();
    Object pointsLock = new Object();
    byte[] kinectData = null;
    int dataWidth, dataHeight;

    int dataSeq, stashSeq;

    // Stash
    byte[] dataStash;
    int stashWidth, stashHeight;
    //BufferedImage r_rgbIm;
    //BufferedImage r_depthIm;
    ArrayList<double[]> points;

    // Calibration
    Config color = null;
    Config ir = null;
    View input;
    View output;
    Rasterizer rasterizer;

    // RGB (XXX) Camera parameters, pulled from config
    double Cirx, Ciry, Firx, Firy;

    // Kinect to world transformation and point filtering
    Config robot = null;
    double[][] k2wXform;
    double[][] k2wXform_T;
    april.jmat.geom.Polygon poly;

    public KinectSensor(Config config_) throws IOException{
    	init(config_);
    }

    private void init(Config config_) throws IOException{
        config = config_;

        // Pull out config files
        color = new ConfigFile(config_.getPath("kinect.calib_rgb"));
        color = color.getChild("aprilCameraCalibration.camera0000");
        //ir = new ConfigFile(config_.getPath("kinect.calib_ir"));
        //ir = ir.getChild("aprilCameraCalibration.camera0000");

        if (config_.getString("kinect.calib_robot") != null)
            robot = new ConfigFile(config_.getPath("kinect.calib_robot"));

        // Set RGB Parameters (XXX)
        Cirx = color.requireDoubles("intrinsics.cc")[0];
        Ciry = color.requireDoubles("intrinsics.cc")[1];
        Firx = color.requireDoubles("intrinsics.fc")[0];
        Firy = color.requireDoubles("intrinsics.fc")[1];

        // Create the input view
        System.err.println("NFO: Initializing kinect calibration");
        String classname = color.requireString("class");

        Object obj = ReflectUtil.createObject(classname, color);
        assert (obj != null);
        assert (obj instanceof Calibration);

        input = (Calibration) obj;

        // Create the output view. XXX Eventually specified by config or cmd?
        System.err.println("NFO: Initializing rectified view");
        output = new MaxRectifiedView(input);

        // Create the rasterizer. XXX Eventually specified by config or cmd?
        System.err.println("NFO: Initializing rasterizer");
        rasterizer = new NearestNeighborRasterizer(input, output);

        // Initialize kinect-to-world transform
        robot = null;
        if (robot != null) {
            System.out.println("Loading robot calibration from file...");
            // Create the k2w transform
            k2wXform = new double[4][4];
            double[] k2wArray = robot.getDoubles("calibration.k2w");
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    k2wXform[i][j] = k2wArray[i*4 + j];
                }
            }

            // Build bounding poly
            ArrayList<double[]> polypoints = new ArrayList<double[]>();
            double[] dim = robot.getDoubles("calibration.dim");
            double[] polyArray = robot.getDoubles("calibration.poly");
            for (int i = 0; i < polyArray.length; i+=2) {
                polypoints.add(new double[] {polyArray[i], polyArray[i+1]});
            }
            poly = new april.jmat.geom.Polygon(polypoints);
        } else {
            k2wXform = LinAlg.translate(0, -0.8, 0.7);
            LinAlg.timesEquals(k2wXform, LinAlg.rotateX(-2.42));

            double[] polyArray = new double[] {6, 459, 105, 258, 469, 257, 566, 454};
            ArrayList<double[]> polypoints = new ArrayList<double[]>();
            for (int i = 0; i < polyArray.length; i+=2) {
                polypoints.add(new double[] {polyArray[i], polyArray[i+1]});
            }
            poly = new april.jmat.geom.Polygon(polypoints);
        }
        k2wXform_T = LinAlg.transpose(k2wXform);

        //x Spin up LCM listener
        Ros ros = new Ros();
        ros.connect();

        if (ros.isConnected()) {
            System.out.println("Successfully connected to rosbridge server.");
        }
        else {
            System.out.println("NOT CONNECTED TO ROSBRIDGE");
        }

        Topic kinect_data = new Topic(ros,
                                      "/head_camera/depth_registered/points",
                                      "sensor_msgs/PointCloud2", 1000);

        kinect_data.subscribe(new TopicCallback() {
                @Override
                public void handleMessage(Message message) {
                    try {
                        synchronized(kinectLock) {
                            JsonObject jobj = message.toJsonObject();
                            dataWidth = jobj.getInt("width");
                            dataHeight = jobj.getInt("height");

                            dataSeq = jobj.getJsonObject("header").getInt("seq");
                            kinectData = DatatypeConverter.parseBase64Binary(jobj.getString("data"));
                            for (int i = 0; i < kinectData.length; i++)
                               kinectData[i] = (byte) (kinectData[i] & 0xFF);
                        }
                    } catch (ClassCastException e) {
                         System.out.println("Could not extract data from ROS message");
                    }
                }
            });
    }

    public double[][] getTransform(){
    	return k2wXform;
    }

    public double[] getParams(){
    	return new double[]{Cirx, Ciry, Firx, Firy};
    }

    public int getStashedFrameNum()
    {
        synchronized (kinectLock) {
            return stashSeq;
        }
    }


    /** "Stash" the current kinect frame data, which will then be
     *  used for all subsequent frame lookups. Calling stash again will
     *  replace the currently stashed frame with the most recent frame.
     *  This is also where data is undistorted so as to not waste cycles
     *  processing frames that will never be used.
     */
    public boolean stashFrame()
    {
        // Haven't received a new frame yet
        synchronized (kinectLock) {
            if (kinectData == null)
                return false;

            dataStash = kinectData;
            stashWidth = dataWidth;
            stashHeight = dataHeight;
            kinectData = null;
            stashSeq = dataSeq;
        }

        synchronized (pointsLock) {
            points = new ArrayList<double[]>();

            for (int i = 0; i < 32*dataWidth*dataHeight; i+=32) {
                byte[] x_raw = {dataStash[i+3], dataStash[i+2],
                                dataStash[i+1], dataStash[i+0]};
                float x = ByteBuffer.wrap(x_raw).getFloat();

                byte[] y_raw = {dataStash[i+7], dataStash[i+6],
                                dataStash[i+5], dataStash[i+4]};
                float y = ByteBuffer.wrap(y_raw).getFloat();

                byte[] z_raw = {dataStash[i+11], dataStash[i+10],
                                dataStash[i+9], dataStash[i+8]};
                float z = ByteBuffer.wrap(z_raw).getFloat();

                int red = dataStash[i+16] & 0xff;
                int green = dataStash[i+17] & 0xff;
                int blue = dataStash[i+18] & 0xff;
                int rgb = (red << 16 | green << 8 | blue);

                double[] xyz = {x, y, z};
                double[] wp = k2w(xyz);
                double[] pt = {wp[0], wp[1], wp[2], rgb};
                points.add(pt);
            }
        }

        return true;
    }

    /** Get the bounding polygon */
    public april.jmat.geom.Polygon getPoly()
    {
        return poly;
    }

    /** Get the width of our rectified images */
    public int getWidth()
    {
        if (dataStash == null) return 0;
        return stashWidth;
    }

    public int getHeight()
    {
        if (dataStash == null) return 0;
        return stashHeight;
    }
    // === Get points in the world coordinate system ===

    /** Convert a kinect point to world coordinates */
    private double[] k2w(double[] p)
    {
        double[] pt = LinAlg.resize(p, 4);
        pt[3] = 1;

        return LinAlg.resize(LinAlg.matrixAB(k2wXform, pt), p.length);
    }

    /** Return the real world position/orientation of the camera */
    public double[][] getCameraXform()
    {
        return k2wXform;
    }

    public ArrayList<double[]> getAllXYZRGB(boolean fastScan){
        synchronized (pointsLock) {
            return points;
        }
    }

    /** Sensor interface to colored points */
    public double[] getXYZRGB(int ix, int iy)
    {
         if (ix < 0 || ix >= getWidth() ||
             iy < 0 || iy >= getHeight())
             return new double[4];


        if (poly != null && !poly.contains(new double[]{ix,iy}))
            return new double[4];

         synchronized(pointsLock) {
             return points.get(ix*dataWidth + iy);
        }
    }


    /** Get the 3D point at (ix, iy) in the stashed frame */
    public double[] getLocalXYZ(int ix, int iy)
    {
        return new double[6];
    }

    // === Debug GUI ==============
    public static void main(String[] args)
    {
        GetOpt opts = new GetOpt();
        opts.addBoolean('h',"help",false,"Show this help screen");
        opts.addString('c',"config",null,"Calibration config");

        if (!opts.parse(args)) {
            System.err.println("ERR: Error parsing args. "+opts.getReason());
            System.exit(1);
        }

        if (opts.getBoolean("help") ||
            opts.getString("config") == null)
        {
            opts.doHelp();
            System.exit(0);
        }

        Config config = null;
        // Create camera calibration configs
        try {
            config = new ConfigFile(opts.getString("config"));
        } catch (IOException ioex) {
            System.err.println("ERR: Could not open calibration config");
            ioex.printStackTrace();
            System.exit(1);
        }

        JFrame jf = new JFrame("Kinect GUI");
        jf.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        jf.setLayout(new BorderLayout());
        jf.setSize(800,600);

        VisWorld vw = new VisWorld();
        VisLayer vl = new VisLayer(vw);
        VisCanvas vc = new VisCanvas(vl);
        jf.add(vc, BorderLayout.CENTER);

        jf.setVisible(true);

        KinectSensor kinect = null;
        try {
            kinect = new KinectSensor(config);
        } catch (IOException ioex) {
            System.err.println("ERR: Could not initialize KinectSensor");
            ioex.printStackTrace();
            System.exit(1);
        }

        int fps = 30;
        // while (true) {
        //     TimeUtil.sleep(1000/fps);
        //     if (!kinect.stashFrame())
        //         continue;

        //     ArrayList<double[]> points = new ArrayList<double[]>();
        //     VisColorData vcd = new VisColorData();
        //     for (int y = 0; y < kinect.getHeight(); y++) {
        //         for (int x = 0; x < kinect.getWidth(); x++) {
        //             double[] xyz = kinect.getXYZ(x,y);
        //             int rgb = kinect.getRGB(x,y);

        //             if (xyz == null)
        //                 continue;

        //             points.add(xyz);
        //             vcd.add(rgb);   // XXX these colors are flipped
        //         }
        //     }

        //     VisWorld.Buffer vb = vw.getBuffer("kinect");
        //     vb.addBack(new VzPoints(new VisVertexData(points),
        //                             new VzPoints.Style(vcd, 2)));
        //     vb.swap();
        //}
    }
}
