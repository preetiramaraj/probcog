package probcog.commands.controls;

import java.awt.*;
import javax.swing.*;
import java.io.*;
import java.util.*;

import lcm.lcm.*;

import april.jmat.*;
import april.jmat.geom.*;
import april.vis.*;
import april.util.*;

import probcog.util.*;

import magic2.lcmtypes.*;

/** A utility class for generating and debugging potential functions */
public class PotentialUtil
{
    static final double PENALTY_WEIGHT = 1000;
    static final boolean DEBUG = false;
    static final double DOOR_WEIGHT = 1.0;
    static final double LINEAR_DOOR_WEIGHT = 1.0;
    static final double DOOR_DIST_M = 0.4;

    static public enum AttractivePotential
    {
        LINEAR, QUADRATIC, COMBINED
    }

    // XXX Doorway preservation is slow and not great to use, yet.
    static public enum RepulsivePotential
    {
        CLOSEST_POINT, ALL_POINTS, PRESERVE_DOORS
    }

    static public class Params
    {
        public laser_t laser;
        public double[] robotXYT;
        public double[] goalXYT;

        public Params(laser_t laser, double[] robotXYT, double[] goalXYT)
        {
            this.laser = laser;
            this.robotXYT = robotXYT;
            this.goalXYT = goalXYT;
        }

        // Optional line set by user?
        public GLineSegment2D doorTrough = null;

        // Is this value set correctly in config? Reevaluate for new robot.
        public double robotRadius = Util.getConfig().requireDouble("robot.geometry.radius");
        public double fieldSize = 3.0;  // [meters];
        public double fieldRes = 0.05;   // [meters / pixel]

        // attractiveThreshold used for combined method, specifying a distance
        // that, when exceeded, will switch to linear potential from quadratic.
        public AttractivePotential attractivePotential = AttractivePotential.LINEAR;
        public double attractiveWeight = 2.0;
        public double attractiveThreshold = 1.0;

        public RepulsivePotential repulsivePotential = RepulsivePotential.CLOSEST_POINT;
        public double repulsiveWeight = 1.0;
        public double maxObstacleRange = 5.0*robotRadius;
        public double safetyRange = .5*Util.getConfig().requireDouble("robot.geometry.width")+0.05;
    }

    // Returns gradient_0, gradient_1, and weighted "potential"
    static private double[] gpAttract(double[] rxy,
                                      double[] goal,
                                      Params params)
    {
        double K_aw = params.attractiveWeight;
        double[] rgoal = LinAlg.subtract(goal, rxy);
        double[] gradient = LinAlg.normalize(rgoal);
        double dgoal = LinAlg.magnitude(rgoal);
        gradient = LinAlg.resize(gradient, 3);
        if (dgoal < 1) {
            gradient = LinAlg.scale(gradient, K_aw);
            gradient[2] = K_aw*dgoal*dgoal*0.5;
        } else {
            gradient = LinAlg.scale(gradient, K_aw/dgoal);
            gradient[2] = K_aw*dgoal;
        }

        return gradient;
    }

    static private double[] gpRepulse(double[] rxy,
                                      ArrayList<double[]> points,
                                      Params params)
    {
        double kw = params.repulsiveWeight;
        double kr = params.maxObstacleRange;

        // Compute gradient by using a softmax weighted sum of
        // the components generated by each point. Softmax weights
        // are determined based on the potential any point would have
        // individually contributed. (First pass, try to use mag of grad?)
        ArrayList<double[]> components = new ArrayList<double[]>();
        ArrayList<Double> potentials = new ArrayList<Double>();
        double softmaxSum = 0;
        for (double[] xy: points) {
            // Determine direction of gradient based on point positions
            // relative to the ROBOT. This allows us to do something
            // approximating visiblity checks.
            double[] shiftedPoint = LinAlg.subtract(rxy, xy);
            double[] dir = LinAlg.normalize(shiftedPoint);
            double d = LinAlg.distance(rxy, xy)-params.safetyRange;

            // Project query onto a vector between lidar point and robot
            // and calculate the distance from the point TO the line, as well.
            // If query is on "correct" side of point relative to robot,
            // use normal gradient. Otherwise, switch to gradient for
            // objects behind walls, which attempts to push back through
            // the wall towards the robot, still.
            double[] dirRobot = LinAlg.scale(LinAlg.normalize(xy), -1);
            double d0 = LinAlg.dotProduct(shiftedPoint, dirRobot);
            double d1 = LinAlg.magnitude(LinAlg.subtract(LinAlg.scale(dirRobot, d0), shiftedPoint));

            if (d0 < 0 && d1 < .2) {
                //System.out.println(d0 + " " + d1);
                d = d0 - params.safetyRange;
                dir = dirRobot;
            }

            double gradientMag = 0;
            double potential = 0;
            if (d <= 0) {
                gradientMag = -2*kw/kr;
                potential = kw*(1+gradientMag*d);
            } else if (d < kr) {
                gradientMag = 2*kw/kr*(d/kr - 1);
                potential = kw/(kr*kr)*LinAlg.sq(d-kr);
                //gradientMag = kw*(1/kr - 1/d)*(1/(d*d));
            }

            if (potential == 0)
                continue;
            double[] component = LinAlg.scale(dir, -gradientMag);
            softmaxSum += Math.exp(potential);
            components.add(component);
            potentials.add(potential);
        }

        double[] gradient = new double[2];
        double gp = 0;

        // Make sure there were forces on the obstacle
        if (softmaxSum != 0) {
            for (int i = 0; i < components.size(); i++) {
                double softmax = Math.exp(potentials.get(i))/softmaxSum;
                LinAlg.plusEquals(gradient, LinAlg.scale(components.get(i), softmax));
                gp += softmax * potentials.get(i);
            }
        }

        gradient = LinAlg.resize(gradient, 3);
        gradient[2] = gp;
        return gradient;
    }

    static public double[] getGradientSoftmax(double[] rxy,
                                              double[] goal,
                                              Params params)
    {
        ArrayList<double[]> points = new ArrayList<double[]>();
        for (int i = 0; i < params.laser.nranges; i++) {
            double r = params.laser.ranges[i];
            if (r < 0)
                continue;

            double t = params.laser.rad0 + i*params.laser.radstep;
            points.add(new double[] {r*Math.cos(t), r*Math.sin(t)});
        }

        return getGradientSoftmax(rxy, goal, points, params);
    }

    /** Return a softmax weighted gradient for a point relative to the robot.
     *
     *  @param rxy      Query point in robot local coordinates
     *  @param goal     Goal in robot local coordinates
     *  @param params   Potential field params
     **/
    static public double[] getGradientSoftmax(double[] rxy,
                                              double[] goal,
                                              ArrayList<double[]> points,
                                              Params params)
    {

        double[] gAtt = gpAttract(rxy, goal, params);
        double[] gRep = gpRepulse(rxy, points, params);

        double[] g = LinAlg.add(gAtt, gRep);

        return LinAlg.resize(g, 2);
    }

    static public double getPotentialSoftmax(double[] rxy,
                                             double[] goal,
                                             ArrayList<double[]> points,
                                             Params params)
    {
        double[] gAtt = gpAttract(rxy, goal, params);
        double[] gRep = gpRepulse(rxy, points, params);

        double[] g = LinAlg.add(gAtt, gRep);

        return g[2];
    }

    /** Get a point gradient for a point relative to the robot. No precomputation
     *  of the potential field.
     *
     *  @param rxy      Robot relative coordinate
     *  @param goal     Goal relative to robot
     *  @param params   Potential field params
     */
    static public double[] getGradient(double[] rxy,
                                       double[] goal,
                                       Params params)
    {
        double eps = 0.00001;
        double v00 = getRelative(rxy[0], rxy[1], goal, params);
        double v10 = getRelative(rxy[0]+eps, rxy[1], goal, params);
        double v01 = getRelative(rxy[0], rxy[1]+eps, goal, params);
        double v11 = getRelative(rxy[0]+eps, rxy[1]+eps, goal, params);

        if (v00 == Double.MAX_VALUE || v10 == Double.MAX_VALUE ||
            v01 == Double.MAX_VALUE || v11 == Double.MAX_VALUE)
            return new double[2];

        double dx = 0.5*((v10-v00)+(v11-v01))/eps;
        double dy = 0.5*((v01-v00)+(v11-v10))/eps;

        return new double[] {-dx, -dy};
    }

    static public double getRelative(double rx, double ry, double[] goal, Params params)
    {
        double dist = Math.sqrt(LinAlg.sq(rx-goal[0]) + LinAlg.sq(ry-goal[1]));

        double p_att = getAttractivePotential(dist, params);
        double p_rep = 0;
        switch (params.repulsivePotential) {
            case CLOSEST_POINT:
                p_rep = getRepulsivePotential(rx, ry, params);
                break;
            case ALL_POINTS:
                p_rep = getRepulsiveAllPoints(rx, ry, params);
                break;
            case PRESERVE_DOORS:
                p_rep = getRepulsiveAllPoints(rx, ry, params);
                p_rep += getRepulsivePreservingDoors(rx, ry, params);
                break;
        }

        double p = p_att + p_rep;
        if (Double.isInfinite(p))
            p = Double.MAX_VALUE;

        return p;
    }

    static private double getAttractivePotential(double dist, Params params)
    {
        double kw = params.attractiveWeight;
        double kt = params.attractiveThreshold;

        switch (params.attractivePotential) {
            case LINEAR:
                return dist*kw;
            case QUADRATIC:
                return 0.5*dist*dist*kw;
            case COMBINED:
                if (dist > kt) {
                    return kt*kw*(dist-0.5*kt);
                } else {
                    return 0.5*dist*dist*kw;
                }
            default:
                System.err.println("ERR: Unknown attractive potential");
                return 0;
        }
    }

    // XXX Room for savings...preprocess lasers
    static private double getRepulsivePotential(double rx, double ry, Params params)
    {
        double max = 0;
        for (int i = 0; i < params.laser.nranges; i++) {
            double r = params.laser.ranges[i];
            if (r < 0)
                continue;
            double t = params.laser.rad0 + i*params.laser.radstep;
            double d = Math.sqrt(LinAlg.sq(rx-r*Math.cos(t)) + LinAlg.sq(ry-r*Math.sin(t)));

            double p = repulsiveForce(d, params);
            max = Math.max(p, max);
        }

        return max;
    }

    static private double getRepulsiveAllPoints(double rx, double ry, Params params)
    {
        double sum = 0;
        int count = 0;
        for (int i = 0; i < params.laser.nranges; i++) {
            double r = params.laser.ranges[i];
            if (r < 0)
                continue;
            double t = params.laser.rad0 + i*params.laser.radstep;
            double d = Math.sqrt(LinAlg.sq(rx-r*Math.cos(t)) + LinAlg.sq(ry-r*Math.sin(t)));

            double p = repulsiveForce(d, params);
            if (p == 0)
                continue;
            sum += p;

            count++;
        }

        if (count <= 0)
            return 0;
        return sum/count;
    }

    /** Use the line provided by the user (between the start point and end
     *  point) to define a trough guiding the robot towards the portal
     **/
    static private double getRepulsivePreservingDoors(double rx, double ry, Params params)
    {
        double kr = DOOR_DIST_M;
        double kw = DOOR_WEIGHT;

        // We know where a point is relative to the robot...how about relative
        // to the line? Our line initially starts in robot local coordinates. Do
        // we assume we convert the start/end points to robot relative? Let's say
        // we do for now...
        double p = 0;
        if (params.doorTrough != null) {
            double d = params.doorTrough.distanceTo(new double[] {rx, ry});
            double newd = Math.max(0, kr-d);
            p = repulsiveForce(newd, kr, kw, params.safetyRange);
            if (newd == 0)
                p += LINEAR_DOOR_WEIGHT*d;
        }

        return p;
    }

    static private double repulsiveForce(double d, Params params)
    {
        double kr = params.maxObstacleRange;
        double kw = params.repulsiveWeight;
        double kmin = params.safetyRange;

        return repulsiveForce(d, kr, kw, kmin);
    }

    static private double repulsiveForce(double d, double kr, double kw, double kmin)
    {
        double p = 0;
        if (d < kmin) {
            //p += PENALTY_WEIGHT*Math.min(d, 0.000001);
        }

        // XXX maxValue is incorrect, but is what we tuned with. :/
        double eps = 0.01;
        double maxValue = 1/kr;
        double weight = kw / maxValue;
        if (d < eps) {
            p += weight*LinAlg.sq(1/eps - 1/kr);
        } else if (d < kr) {
            p += weight*LinAlg.sq(1/d - 1/kr);
            //System.out.println(maxValue + " " + p);
        }
        return p;
    }

    /** Get the gradient of a coordinate relative to the robot for the
     *  given potential field. Return as a normalized direction (also relative
     *  to the robot)
     **/
    static public double[] getGradient(double[] rxy,
                                       PotentialField pf)
    {
        double v00 = pf.getRelative(rxy[0], rxy[1]);
        double v10 = pf.getRelative(rxy[0]+pf.getMPP(), rxy[1]);
        double v01 = pf.getRelative(rxy[0], rxy[1]+pf.getMPP());
        double v11 = pf.getRelative(rxy[0]+pf.getMPP(), rxy[1]+pf.getMPP());

        double dx = 0.5*((v10-v00)+(v11-v01));
        double dy = 0.5*((v01-v00)+(v11-v10));

        if (MathUtil.doubleEquals(Math.abs(dx)+Math.abs(dx), 0))
            return new double[2];
        return LinAlg.normalize(new double[] {-dx, -dy});
    }

    /** Given application specific parameters, generate a potential field
     *  locally centered around the robot.
     **/
    static public PotentialField getPotential(Params params)
    {
        PotentialField pf = new PotentialField(params.robotXYT,
                                               params.fieldSize,
                                               params.fieldSize,
                                               params.fieldRes);

        ArrayList<double[]> points = new ArrayList<double[]>();
        for (int i = 0; i < params.laser.nranges; i++) {
            double r = params.laser.ranges[i];
            if (r < 0)
                continue;

            double t = params.laser.rad0 + i*params.laser.radstep;
            points.add(new double[] {r*Math.cos(t), r*Math.sin(t)});
        }

        //Tic tic = new Tic();
        //addAttractivePotential(params, pf);
        //if (DEBUG)
        //    System.out.printf("\tattractive: %f [s]\n", tic.toctic());
        //addRepulsivePotential(params, pf);
        //if (DEBUG)
        //    System.out.printf("\trepulsive: %f [s]\n", tic.toctic());

        double[] rgoal = LinAlg.resize(LinAlg.xytInvMul31(pf.origin, params.goalXYT), 2);
        for (int y = 0; y < pf.getHeight(); y++) {
            for (int x = 0; x < pf.getWidth(); x++) {
                double[] rxy = pf.indexToRelative(x, y);

                pf.setIndex(x, y, getPotentialSoftmax(rxy, rgoal, points, params));
            }
        }

        return pf;
    }

    /** Add attrative potential to the system in one of three forms.
     *  1) Linear/conical potential. Directly proportional to distance to goal.
     *  2) Quadratic potential. Square of distance to goal.
     *  3) Combined potential. Starts quadratic, but linear beyond some point.
     **/
    static private void addAttractivePotential(Params params,
                                               PotentialField pf)
    {
        double kw = params.attractiveWeight;
        double kt = params.attractiveThreshold;

        for (int y = 0; y < pf.getHeight(); y++) {
            for (int x = 0; x < pf.getWidth(); x++) {
                double[] xy = pf.indexToMeters(x, y);
                double d = LinAlg.distance(xy, params.goalXYT, 2);

                switch (params.attractivePotential) {
                    case LINEAR:
                        pf.addIndexUnsafe(x, y, d*kw);
                        break;
                    case QUADRATIC:
                        pf.addIndexUnsafe(x, y, 0.5*d*d*kw);
                        break;
                    case COMBINED:
                        if (d > kt) {
                            pf.addIndexUnsafe(x, y, kt*kw*(d-0.5*kt));
                        } else {
                            pf.addIndexUnsafe(x, y, 0.5*d*d*kw);
                        }
                        break;
                    default:
                        System.err.println("ERR: Unknown attractive potential");
                }
            }
        }
    }

    /** Generate repulsive potential based on obstacles observed near the robot.
     *  Derives these range measurements from the provided laser_t in params.
     **/
    static private void addRepulsivePotential(Params params,
                                              PotentialField pf)
    {
        double[] xyt = params.robotXYT;
        double[] invXyt = LinAlg.xytInverse(xyt);
        double sz = params.fieldSize/2;
        double maxRange = params.maxObstacleRange;

        // Convert laser_t measurements to global coordinates. Ignore ranges
        // that cannot contribute to our potential.
        ArrayList<double[]> points = new ArrayList<double[]>();
        for (int i = 0; i < params.laser.nranges; i++) {
            double r = params.laser.ranges[i];

            // Error value
            if (r < 0)
                continue;

            if (r > maxRange + sz)
                continue;

            double t = params.laser.rad0 + i*params.laser.radstep;
            double[] xy = new double[] { r*Math.cos(t), r*Math.sin(t) };
            points.add(LinAlg.transform(xyt, xy));
        }

        switch (params.repulsivePotential) {
            case CLOSEST_POINT:
                repulsiveClosestPoint(pf, points, params);
                break;
            case ALL_POINTS:
                repulsiveAllPoints(pf, points, params);
                break;
            case PRESERVE_DOORS:
                repulsivePreserveDoors(pf, points, params);
                break;
            default:
                System.err.println("ERR: Unknown repulsive potential");
        }
    }

    static private void repulsiveClosestPoint(PotentialField pf,
                                              ArrayList<double[]> points,
                                              Params params)
    {
        int h = pf.getHeight();
        int w = pf.getWidth();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] xy = pf.indexToMeters(x, y);
                double max = 0;
                for (double[] pxy: points) {
                    double d = LinAlg.distance(xy, pxy, 2);
                    double p = repulsiveForce(d, params);
                    max = Math.max(p, max);
                }
                pf.addIndexUnsafe(x, y, max);
            }
        }
    }

    static private void repulsiveAllPoints(PotentialField pf,
                                           ArrayList<double[]> points,
                                           Params params)
    {
        int h = pf.getHeight();
        int w = pf.getWidth();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] xy = pf.indexToMeters(x, y);
                double v = 0;
                int cnt = 0;
                for (double[] pxy: points) {
                    double d = LinAlg.distance(xy, pxy, 2);
                    double p = repulsiveForce(d, params);

                    if (p == 0)
                        continue;
                    v += p;
                    cnt++;
                }
                if (cnt > 0)
                    pf.addIndexUnsafe(x, y, v/cnt);
            }
        }
    }

    static private void repulsivePreserveDoors(PotentialField pf,
                                               ArrayList<double[]> points,
                                               Params params)
    {
        repulsiveAllPoints(pf, points, params);

        if (params.doorTrough == null)
            return;

        double kr = DOOR_DIST_M;
        double kw = DOOR_WEIGHT;

        int h = pf.getHeight();
        int w = pf.getWidth();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] rxy = pf.indexToRelative(x, y);
                double d = params.doorTrough.distanceTo(rxy);
                double newd = Math.max(0, kr-d);
                double p = repulsiveForce(newd, kr, kw, params.safetyRange);
                if (newd == 0)
                    p += LINEAR_DOOR_WEIGHT*d;

                pf.addIndexUnsafe(x, y, p); // XXX Huge outside of trough
            }
        }
    }

    static public void main(String[] args)
    {
        double[] goal = new double[] {10, 20, 0};
        double[] xyt = new double[] {0, 0, 0};

        // Fake a hallway. Wall on right is 1m away, wall on left is 0.5m
        ArrayList<double[]> points = new ArrayList<double[]>();
        laser_t laser = new laser_t();
        laser.rad0 = (float)(-3*Math.PI/4);
        laser.radstep = (float)(Math.toRadians(.25));
        laser.nranges = (int)(Math.ceil(2*Math.abs(laser.rad0)/laser.radstep));
        laser.ranges = new float[laser.nranges];
        double doorOffset = 0.0;
        double doorSize = 0.9;
        for (int i = 0; i < laser.nranges; i++) {
            double t = laser.rad0 + i*laser.radstep;
            double r = -1;
            if (t < 0) {
                r = (-0.5/Math.sin(t));
                if (r*Math.cos(t) > doorOffset && r*Math.cos(t) < doorOffset+doorSize)
                    r = -1;
            } else if (t > 0) {
                r = (1.0/Math.sin(t));
            }
            if (r > 30 || r < 0)
                r = -1;

            laser.ranges[i] = (float)r;
            if (r > 0)
                points.add(new double[] {r*Math.cos(t), r*Math.sin(t)});
        }

        LCM.getSingleton().publish("TEST_LASER", laser);

        // Construct the potential field
        Params params = new Params(laser, xyt, goal);
        params.attractivePotential = AttractivePotential.COMBINED;
        params.fieldSize = 10.0;
        params.fieldRes = 0.10;
        params.attractiveWeight = 1.0;
        params.repulsiveWeight = 1.0;
        params.repulsivePotential = RepulsivePotential.ALL_POINTS;
        params.doorTrough = new GLineSegment2D(new double[] {doorOffset+.5*doorSize,0}, new double[] {goal[0], goal[1]});
        params.maxObstacleRange = 0.5;

        // Wait for keypress
        try {
            System.out.println("Press ENTER to continue:");
            System.in.read();
        } catch (IOException ioex) {}

        Tic tic = new Tic();
        PotentialField pf = PotentialUtil.getPotential(params);
        System.out.printf("Computation completed in %f [s]\n", tic.toc());

        // Evaluate gradients at fixed locations around the robot
        double sz = params.fieldSize/2 - 2*params.fieldRes;
        ArrayList<double[]> rxys = new ArrayList<double[]>();
        for (double y = -sz; y <= sz; y+= 2*params.fieldRes) {
            for (double x = -sz; x <= sz; x+= 2*params.fieldRes) {
                rxys.add(new double[] {x, y});
            }
        }

        ArrayList<double[]> gradients = new ArrayList<double[]>();
        for (double[] rxy: rxys) {
            //gradients.add(getGradient(rxy, pf));
            // XXX
            double[] grad = getGradientSoftmax(rxy, LinAlg.resize(goal, 2), points, params);
            assert (!(Double.isNaN(grad[0]) || Double.isNaN(grad[1])));
            assert (!(Double.isInfinite(grad[0]) || Double.isInfinite(grad[1])));
            gradients.add(LinAlg.scale(grad, 1));
            //gradients.add(LinAlg.normalize(grad));
        }


        JFrame jf = new JFrame("Potential test");
        jf.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        jf.setLayout(new BorderLayout());
        jf.setSize(800, 600);

        VisWorld vw = new VisWorld();
        VisLayer vl = new VisLayer(vw);
        VisCanvas vc = new VisCanvas(vl);
        jf.add(vc, BorderLayout.CENTER);

        // Render the field
        int[] map = new int[] {0xffffff00,
                               0xffff00ff,
                               0x0007ffff,
                               0xff0000ff,
                               0xff2222ff};
        double minVal = pf.getMinValue();
        double maxVal = minVal+params.repulsiveWeight+params.fieldSize*params.attractiveWeight;
        ColorMapper cm = new ColorMapper(map, minVal, maxVal);

        double[][] M = LinAlg.xytToMatrix(xyt);
        VisWorld.Buffer vb = vw.getBuffer("potential-field");
        vb.setDrawOrder(-10);
        vb.addBack(new VisChain(M, pf.getVisObject(cm)));
        vb.swap();

        // Render a grid
        vb = vw.getBuffer("grid");
        vb.addBack(new VzGrid());
        vb.swap();

        // Render a robot
        vb = vw.getBuffer("robot");
        vb.setDrawOrder(10);
        vb.addBack(new VisChain(M, new VzRobot(new VzMesh.Style(Color.green))));
        vb.swap();

        // Goal
        vb = vw.getBuffer("goal");
        vb.setDrawOrder(10);
        vb.addBack(new VisChain(LinAlg.xytToMatrix(goal),
                                LinAlg.scale(0.2),
                                new VzSphere(new VzMesh.Style(Color.green))));
        vb.swap();

        // Render some local potentials
        vb = vw.getBuffer("gradients");
        vb.setDrawOrder(20);
        ArrayList<double[]> bpoints = new ArrayList<double[]>();
        ArrayList<double[]> gpoints = new ArrayList<double[]>();
        for (int i = 0; i < rxys.size(); i++) {
            double[] rxy = rxys.get(i);
            double[] u = gradients.get(i);

            double[] p0 = LinAlg.transform(M, rxy);
            double[] p1 = LinAlg.transform(M, LinAlg.add(LinAlg.scale(u, 1*params.fieldRes), rxy));
            bpoints.add(p0);
            gpoints.add(p0);
            gpoints.add(p1);
        }
        vb.addBack(new VzPoints(new VisVertexData(bpoints),
                                new VzPoints.Style(Color.black, 2)));
        vb.addBack(new VzLines(new VisVertexData(gpoints),
                               VzLines.LINES,
                               new VzLines.Style(Color.gray, 1)));
        vb.swap();

        vb = vw.getBuffer("laser");
        vb.setDrawOrder(100);
        ArrayList<double[]> lpoints = new ArrayList<double[]>();
        for (int i = 0; i < laser.nranges; i++) {
            double r = laser.ranges[i];
            if (r < 0)
                continue;

            double t = laser.rad0 + i*laser.radstep;
            lpoints.add(new double[] {r*Math.cos(t), r*Math.sin(t)});
        }
        vb.addBack(new VisChain(M,
                                new VzPoints(new VisVertexData(lpoints),
                                             new VzPoints.Style(Color.orange, 3))));
        vb.swap();

        jf.setVisible(true);
    }
}
