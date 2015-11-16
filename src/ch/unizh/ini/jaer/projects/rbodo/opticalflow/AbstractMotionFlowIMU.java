package ch.unizh.ini.jaer.projects.rbodo.opticalflow;

import com.jmatio.io.MatFileReader;
import com.jmatio.io.MatFileWriter;
import com.jmatio.types.MLDouble;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.util.gl2.GLUT;
import eu.seebetter.ini.chips.davis.imu.IMUSample;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Observable;
import java.util.Observer;
import java.util.logging.Level;
import javax.swing.JFileChooser;
import net.sf.jaer.Description;
import net.sf.jaer.DevelopmentStatus;
import net.sf.jaer.chip.AEChip;
import net.sf.jaer.event.EventPacket;
import net.sf.jaer.event.OutputEventIterator;
import net.sf.jaer.event.PolarityEvent;
import net.sf.jaer.event.orientation.ApsDvsMotionOrientationEvent;
import net.sf.jaer.event.orientation.MotionOrientationEventInterface;
import net.sf.jaer.eventio.AEFileInputStream;
import net.sf.jaer.eventio.AEInputStream;
import static net.sf.jaer.eventprocessing.EventFilter.log;
import net.sf.jaer.eventprocessing.EventFilter2D;
import net.sf.jaer.graphics.AEViewer;
import net.sf.jaer.graphics.AbstractAEPlayer;
import net.sf.jaer.graphics.FrameAnnotater;
import net.sf.jaer.util.DrawGL;

/**
 * Abstract base class for motion flow filters. 
 * The filters that extend this class use different methods to compute the
 * optical flow vectors and override the filterPacket method in this class.
 * Several methods were taken from AbstractDirectionSelectiveFilter and Steadicam
 * and slightly modified. 
 * @author rbodo
 */

@Description("Abstract base class for motion optical flow.")
@DevelopmentStatus(DevelopmentStatus.Status.Abstract)
abstract public class AbstractMotionFlowIMU extends EventFilter2D implements Observer, FrameAnnotater, PropertyChangeListener {
    // Observed motion flow.
    static float vx, vy, v;
    
    int numInputTypes;

    // Basic event information.
    int x, y, ts, type, lastTs;
    
    // (Subsampled) chip sizes.
    int sizex, sizey, subSizeX, subSizeY;
    
    // Subsampling
    int subSampleShift = getInt("subSampleShift",0);
    boolean[][] subsampledPixelIsSet;

    // Map of input orientation event times 
    // [x][y][type] where type is mixture of orienation and polarity.
    int[][][] lastTimesMap; 
    
    // xyFilter.
    private int xMin = getInt("xMin",0);
    int xMax = getInt("xMax",sizex);
    private int yMin = getInt("yMin",0);
    int yMax = getInt("yMax",sizey);
    
    // Display
    private boolean showVectorsEnabled = getBoolean("showVectorsEnabled",true);
    private boolean showRawInputEnabled = getBoolean("showRawInputEnabled",true);
    
    private float ppsScale = getFloat("ppsScale",1f);
  
    // A pixel can fire an event only after this period. Used for smoother flow
    // and speedup.
    int refractoryPeriodUs = getInt("refractoryPeriodUs",50000);
    
    // Global translation, rotation and expansion.
    boolean showGlobalEnabled = getBoolean("showGlobalEnabled",true);
    
    // The output events, also used for rendering output events.
    EventPacket dirPacket; 
    OutputEventIterator outItr; 
    PolarityEvent e;
    ApsDvsMotionOrientationEvent eout;
            
    // Use IMU gyro values to estimate motion flow.
    ImuFlowEstimator imuFlowEstimator;
    
    // Focal length of camera lens needed to convert rad/s to pixel/s.
    // Conversion factor is atan(pixelWidth/focalLength).
    private final static float lensFocalLengthMm = 4.5f;
    
    private boolean addedViewerPropertyChangeListener = false;
    private boolean addTimeStampsResetPropertyChangeListener = false;
    
    // Labels for setPropertyTooltip.
    final String disp = "Display";
    final String imu = "IMU";
    final String smoo = "Smoothing";

    // Performing statistics and logging results. lastLoggingFolder starts off 
    // at user.dir which is startup folder "host/java" where .exe launcher lives
    private String loggingFolder = getPrefs().get("DataLogger.loggingFolder", System.getProperty("user.dir"));
    boolean measureAccuracy = getBoolean("measureAccuracy",false);
    boolean measureProcessingTime = getBoolean("measureProcessingTime",false);
    int countIn, countOut;
    MotionFlowStatistics motionFlowStatistics;
    
    double[][] vxGTframe, vyGTframe, tsGTframe;
    float vxGT, vyGT, vGT;
    private boolean importedGTfromMatlab;
    
    // Discard events that are considerably faster than average
    private float avgSpeed = 0;
    boolean speedControlEnabled = getBoolean("speedControlEnabled",true);
    private float speedMixingFactor = getFloat("speedMixingFactor",1e-3f);
    private float excessSpeedRejectFactor = getFloat("excessSpeedRejectFactor",2f);

    // Motion flow vectors can be filtered out if the angle between the observed 
    // optical flow and ground truth is greater than a certain threshold.
    boolean discardOutliersEnabled = getBoolean("discardOutliersEnabled",false);
    // Threshold angle in degree. Discard measured optical flow vector if it 
    // deviates from ground truth by more than epsilon.
    float epsilon = getFloat("epsilon",10f);
    
    final String filterClassName;
    
    private boolean exportedFlowToMatlab;
    private double[][] vxOut = null;
    private double[][] vyOut = null;
    
    Iterator inItr;
    
    public AbstractMotionFlowIMU(AEChip chip) {
        super(chip);
        addObservers(chip);
        imuFlowEstimator = new ImuFlowEstimator();
        dirPacket = new EventPacket(ApsDvsMotionOrientationEvent.class);      
        filterClassName = getClass().getSimpleName();
        motionFlowStatistics = new MotionFlowStatistics(filterClassName,subSizeX,subSizeY);
        
        setPropertyTooltip("measureAccuracy","writes a txt file with various motion statistics");
        setPropertyTooltip("measureProcessingTime", "writes a txt file with the packet's mean processing time of an event");
        setPropertyTooltip("loggingFolder", "directory to store logged data files");
        setPropertyTooltip(disp,"ppsScale","scale of pixels per second to draw local and global motion vectors");
        setPropertyTooltip(disp,"showVectorsEnabled","shows local motion vectors");
        setPropertyTooltip(disp,"showGlobalEnabled","shows global tranlational, rotational, and expansive motion");
        setPropertyTooltip(disp,"showRawInputEnabled","shows the input events, instead of the motion types");
        setPropertyTooltip(disp,"xMin","events with x-coordinate below this are filtered out.");
        setPropertyTooltip(disp,"xMax","events with x-coordinate above this are filtered out.");
        setPropertyTooltip(disp,"yMin","events with y-coordinate below this are filtered out.");
        setPropertyTooltip(disp,"yMax","events with y-coordinate above this are filtered out.");
        setPropertyTooltip(smoo,"subSampleShift","shift subsampled timestamp map stores by this many bits");
        setPropertyTooltip(smoo,"refractoryPeriodUs","compute motion only if the pixel didn't fire during this period.");
        setPropertyTooltip(smoo,"speedControlEnabled","enables filtering of excess speeds");
        setPropertyTooltip(smoo,"speedControl_ExcessSpeedRejectFactor","local speeds this factor higher than average are rejected as non-physical");
        setPropertyTooltip(smoo,"speedControl_speedMixingFactor","speeds computed are mixed with old values with this factor");
        setPropertyTooltip(imu,"discardOutliersEnabled","discard measured local motion vector if it deviates from IMU estimate");
        setPropertyTooltip(imu,"epsilon","threshold angle in degree. Discard measured optical flow vector if it deviates from IMU-estimate by more than epsilon");
        // check lastLoggingFolder to see if it really exists, if not, default to user.dir
        File lf = new File(loggingFolder);
        if (!lf.exists() || !lf.isDirectory()) {
            log.log(Level.WARNING, "loggingFolder {0} doesn't exist or isn't a directory, defaulting to {1}", new Object[]{lf,lf});
            setLoggingFolder(System.getProperty("user.dir"));
        }
    }
        
    synchronized public void doSelectLoggingFolder() {
        if (loggingFolder == null || loggingFolder.isEmpty())
            loggingFolder = System.getProperty("user.dir");
        JFileChooser chooser = new JFileChooser(loggingFolder);
        chooser.setDialogTitle("Choose data logging folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        if (chooser.showOpenDialog(getChip().getAeViewer().getFilterFrame()) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            if (f != null && f.isDirectory()) {
                setLoggingFolder(f.toString());
                log.log(Level.INFO, "Selected data logging folder {0}", loggingFolder);
            } else log.log(Level.WARNING, "Tried to select invalid logging folder named {0}", f);
        }
    }
    
    public final void addObservers(AEChip chip) {chip.addObserver(this);}
    
    public final void addListeners(AEChip chip) {
        if (chip.getAeViewer() != null) {
            if (!addedViewerPropertyChangeListener) {
                chip.getAeViewer().addPropertyChangeListener(this);
                addedViewerPropertyChangeListener = true;
            }
            if (!addTimeStampsResetPropertyChangeListener) {
                chip.getAeViewer().addPropertyChangeListener(AEViewer.EVENT_TIMESTAMPS_RESET,this);
                addTimeStampsResetPropertyChangeListener = true;
            }
        }
    }
    
    // Allows importing two 2D-arrays containing the x-/y- components of the 
    // motion flow field used as ground truth.
    synchronized public void doImportGTfromMatlab() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose ground truth file");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        if (chooser.showOpenDialog(chip.getAeViewer().getFilterFrame()) == JFileChooser.APPROVE_OPTION) {
            try {
                vxGTframe = ((MLDouble) (new MatFileReader(chooser.getSelectedFile().getPath())).getMLArray("vxGT")).getArray();
                vyGTframe = ((MLDouble) (new MatFileReader(chooser.getSelectedFile().getPath())).getMLArray("vyGT")).getArray();
                tsGTframe = ((MLDouble) (new MatFileReader(chooser.getSelectedFile().getPath())).getMLArray("ts")).getArray();
                importedGTfromMatlab = true;
                log.info("Imported ground truth file");
            } catch (IOException ex) {log.log(Level.SEVERE,null,ex);}
        }
    }
    
    // Allows exporting flow vectors that were accumulated between tmin and tmax
    // to a mat-file which can be processed in MATLAB.
    public void exportFlowToMatlab(final int tmin, final int tmax) {
        if (!exportedFlowToMatlab) {
            int firstTs = dirPacket.getFirstTimestamp();
            if (firstTs > tmin && firstTs < tmax) {
                if (vxOut == null) {
                    vxOut = new double[sizey][sizex];
                    vyOut = new double[sizey][sizex];
                }
                for (Object o : dirPacket) {
                    ApsDvsMotionOrientationEvent ev = (ApsDvsMotionOrientationEvent) o;
                    if (ev.hasDirection) {
                        vxOut[ev.y][ev.x] = ev.velocity.x;
                        vyOut[ev.y][ev.x] = ev.velocity.y;
                    }
                }
            }
            if (firstTs > tmax && vxOut != null) {
                ArrayList list = new ArrayList();
                list.add(new MLDouble("vx",vxOut));
                list.add(new MLDouble("vy",vyOut));
                try {
                    MatFileWriter matFileWriter = new MatFileWriter(loggingFolder+"/flowExport.mat", list);
                } catch (IOException ex) {log.log(Level.SEVERE, null, ex);}
                log.log(Level.INFO,"Exported motion flow to {0}/flowExport.mat",loggingFolder);
                exportedFlowToMatlab = true;
                vxOut = null;
                vyOut = null;
            }
        }
    }
    
    // This function is called for every event to assign the local ground truth
    // (vxGT,vyGT) at location (x,y) a value from the imported ground truth field
    // (vxGTframe,vyGTframe).
    void setGroundTruth() {
        if (importedGTfromMatlab) {
            if (ts >= tsGTframe[0][0] && ts < tsGTframe[0][1]) {
                vxGT = (float) vxGTframe[y][x];
                vyGT = (float) vyGTframe[y][x];
            } else {
                vxGT = 0;
                vyGT = 0;
            }
        } else {
            vxGT = imuFlowEstimator.getVx();
            vyGT = imuFlowEstimator.getVy();
        }
        vGT = (float) Math.sqrt(vxGT*vxGT + vyGT*vyGT);
    }
    
    void resetGroundTruth() {
        importedGTfromMatlab = false;
        vxGTframe = null;
        vyGTframe = null;
        tsGTframe = null;
        vxGT = 0;
        vyGT = 0;
        vGT = 0;
    }
    
    synchronized public void doResetGroundTruth() {resetGroundTruth();}

    // <editor-fold defaultstate="collapsed" desc="ImuFlowEstimator Class">    
    public class ImuFlowEstimator {  
        // Motion flow from IMU gyro values.
        private float vx;
        private float vy;
        private float v;
        
        // Delta time between current timestamp and lastIMUTimestamp, in seconds.
        private float dtS;
        private int lastTsIMU;
        private int tsIMU;
    
        // Focal length of camera lens needed to convert rad/s to pixel/s.
        // Conversion factor is atan(pixelWidth/focalLength).
        private float radPerPixel;

        // Highpass filters for angular rates.   
        private float panRate, tiltRate, rollRate; // In deg/s
        private float panTranslation;
        private float tiltTranslation;
        private float rollRotationRad;
        private boolean initialized = false;

        // Calibration
        private boolean calibrating = false; // used to flag calibration state
        private final int CALIBRATION_SAMPLES = 800; // Samples/s
        private final Measurand panCalibrator, tiltCalibrator, rollCalibrator;
        private float panOffset;
        private float tiltOffset;
        private float rollOffset;

        // Deal with leftover IMU data after timestamps reset
        private static final int FLUSH_COUNT = 10;
        private int flushCounter;
    
        private int nx, ny;
        private float newx, newy;
        
        protected ImuFlowEstimator() {
            panCalibrator = new Measurand();
            tiltCalibrator = new Measurand();
            rollCalibrator = new Measurand(); 
            panOffset = 0.7216f;
            tiltOffset = 3.4707f;
            rollOffset = -0.2576f;
            reset();
        }

        protected final synchronized void reset() {
            flushCounter = FLUSH_COUNT;
            panRate = 0;
            tiltRate = 0;
            rollRate = 0;
            panTranslation = 0;
            tiltTranslation = 0;
            rollRotationRad = 0;
            radPerPixel = (float) Math.atan(chip.getPixelWidthUm()/(1000*lensFocalLengthMm));
            initialized = false;
            vx = 0;
            vy = 0;
            v = 0;
        }
            
        float getVx() {return vx;}
        float getVy() {return vy;}
        float getV() {return v;}
        
        boolean isCalibrationSet() {
            return rollOffset != 0 || tiltOffset != 0 || panOffset != 0;
        }
        
        /**
         * Computes transform using current gyro outputs based on timestamp supplied.
         * @param imuSample
         * @return true if it updated the transformation.
         */
        synchronized protected boolean updateTransform(IMUSample imuSample) {
            if (imuSample == null) return false;

            // flush some samples if the timestamps have been reset 
            // and we need to discard some samples here
            if (flushCounter-- >= 0) return false;

            tsIMU = imuSample.getTimestampUs();
            dtS = (tsIMU-lastTsIMU)*1e-6f;
            lastTsIMU = tsIMU;

            if (!initialized) {
                initialized = true;
                return false;
            }

            panRate  = imuSample.getGyroYawY();
            tiltRate = imuSample.getGyroTiltX();
            rollRate = imuSample.getGyroRollZ();

            if (calibrating) {
                if (panCalibrator.n > CALIBRATION_SAMPLES) {
                    calibrating = false;
                    panOffset = panCalibrator.getMean();
                    tiltOffset = tiltCalibrator.getMean();
                    rollOffset = rollCalibrator.getMean();
                    log.info(String.format("calibration finished. %d samples averaged"
                            + " to (pan,tilt,roll)=(%.3f,%.3f,%.3f)", 
                            CALIBRATION_SAMPLES, panOffset, tiltOffset, rollOffset));
                } else {
                    panCalibrator.update(panRate);
                    tiltCalibrator.update(tiltRate);
                    rollCalibrator.update(rollRate);
                }
                return false;
            }

            panTranslation  = (float) (Math.PI/180)*(panRate-panOffset)*dtS/radPerPixel;
            tiltTranslation = (float) (Math.PI/180)*(tiltRate-tiltOffset)*dtS/radPerPixel;
            rollRotationRad = (float) (Math.PI/180)*(rollOffset-rollRate)*dtS;
            return true;
        }
        
        /** 
         * Get translation and rotation from updateTransform(), 
         * then calculate motion flow by comparing transformed to old event. 
         * @param pe PolarityEvent
         */ 
        protected void calculateImuFlow(PolarityEvent pe) {
            if (pe instanceof IMUSample) {
                IMUSample s = (IMUSample) pe;
                if (s.imuSampleEvent) updateTransform(s);
            }
            if (dtS == 0) dtS = 1;
            // Apply transform R*e+T. 
            // First center events from middle of array at (0,0), 
            // then transform, then move them back to their origin.
            nx = e.x - sizex/2;
            ny = e.y - sizey/2;
            newx = (float) (Math.cos(rollRotationRad)*nx - Math.sin(rollRotationRad)*ny + panTranslation);
            newy = (float) (Math.sin(rollRotationRad)*nx + Math.cos(rollRotationRad)*ny + tiltTranslation);
            vx = (nx - newx)/dtS;
            vy = (ny - newy)/dtS;
            v = (float) Math.sqrt(vx*vx + vy*vy);
        }
    }
    // </editor-fold>
    
    @Override public abstract EventPacket filterPacket(EventPacket in);
    
    synchronized void allocateMap() {
        subsampledPixelIsSet = new boolean[subSizeX][subSizeY];
        lastTimesMap = new int[subSizeX][subSizeY][numInputTypes];
        motionFlowStatistics.globalMotion.reset(subSizeX,subSizeY);
        log.info("Reallocated filter storage after parameter change or reset.");
    }
        
    @Override public synchronized void resetFilter() {
        sizex = chip.getSizeX();
        sizey = chip.getSizeY();
        subSizeX = sizex >> subSampleShift;
        subSizeY = sizey >> subSampleShift;
        motionFlowStatistics.reset(subSizeX,subSizeY);
        imuFlowEstimator.reset();
        exportedFlowToMatlab = false;
        allocateMap();
        if ("DirectionSelectiveFlow".equals(filterClassName)) 
            getEnclosedFilter().resetFilter();
    }
    
    @Override public void initFilter() {resetFilter();}

    @Override public void update(Observable o, Object arg) {initFilter();}

    @Override public void propertyChange(PropertyChangeEvent evt) {
        if (this.filterEnabled)
            switch (evt.getPropertyName()) {
                case AEViewer.EVENT_TIMESTAMPS_RESET:
                    resetFilter();
                    break;
                case AEInputStream.EVENT_REWIND:
                    if (measureAccuracy || measureProcessingTime) doTriggerLogging();
                    resetFilter();
                    break;
                case AEViewer.EVENT_FILEOPEN:
                    log.info("File Open");
                    AbstractAEPlayer player = chip.getAeViewer().getAePlayer();
                    AEFileInputStream in = (player.getAEInputStream());
                    in.getSupport().addPropertyChangeListener(this);
                    // Treat FileOpen same as a rewind
                    resetFilter();
                    break;
            }
    }
            
    /** 
     * Plots a single motion vector which is the number of pixels per second times scaling.
     * Color vectors by angle to x-axis.
     * @param gl the OpenGL context
     * @param e the event
     */
    protected void drawMotionVector(GL2 gl, MotionOrientationEventInterface e) {
        float angle = (float) (Math.atan2(e.getVelocity().y,e.getVelocity().x)/(2*Math.PI)+0.5); 
        gl.glColor3f(angle,1-angle,1/(1+10*angle));
        gl.glPushMatrix();
        DrawGL.drawVector(gl,e.getX(),e.getY(),e.getVelocity().x,e.getVelocity().y,1,ppsScale);
        gl.glPopMatrix();   
    }
        
    @Override public void annotate(GLAutoDrawable drawable) {
        if (!isFilterEnabled()) return;

        GL2 gl = drawable.getGL().getGL2();
        if (gl == null) return;

        checkBlend(gl);

        if (isShowGlobalEnabled()) {
            gl.glLineWidth(4f);
            gl.glColor3f(1,1,1);            
    
            // Draw global translation vector
            gl.glPushMatrix();
            DrawGL.drawVector(gl, sizex/2, sizey/2, 
                              motionFlowStatistics.globalMotion.meanGlobalVx, 
                              motionFlowStatistics.globalMotion.meanGlobalVy, 
                              4, ppsScale);
            gl.glRasterPos2i(2,10);
            chip.getCanvas().getGlut().glutBitmapString(GLUT.BITMAP_HELVETICA_18, 
                String.format("glob. speed=%.2f ", Math.sqrt(
                    Math.pow(motionFlowStatistics.globalMotion.meanGlobalVx,2) +
                    Math.pow(motionFlowStatistics.globalMotion.meanGlobalVy,2))));
            gl.glPopMatrix();
            
            // Draw global rotation vector as line left/right
            gl.glPushMatrix();
            DrawGL.drawLine(gl, sizex/2, sizey*3/4, -motionFlowStatistics.globalMotion.meanGlobalRotation,
                            0, ppsScale*chip.getMaxSize());
            gl.glPopMatrix();
            
            // Draw global expansion as circle with radius proportional to 
            // expansion metric, smaller for contraction, larger for expansion
            gl.glPushMatrix();
            DrawGL.drawCircle(gl, sizex/2, sizey/2, ppsScale*chip.getMaxSize()*
                (1 + motionFlowStatistics.globalMotion.meanGlobalExpansion)/4, 15);
            gl.glPopMatrix();
        }
        
        // Draw individual motion vectors
        if (dirPacket != null && isShowVectorsEnabled()){
            gl.glLineWidth(2f);
            for(Object o : dirPacket){
                MotionOrientationEventInterface ei = (MotionOrientationEventInterface) o;
                // If we passAllEvents then the check is needed to not annotate 
                // the events without a real direction.
                if (ei.isHasDirection()) drawMotionVector(gl,ei);
            }
        }
        
        gl.glLineWidth(2f);
        gl.glColor3f(0,0,1);            
    
        // Display statistics
        if (measureProcessingTime) {
            gl.glPushMatrix();
            gl.glRasterPos2i(240,0);
            chip.getCanvas().getGlut().glutBitmapString(GLUT.BITMAP_HELVETICA_18, 
                String.format("%4.2f +/- %5.2f us", new Object[]{
                    motionFlowStatistics.processingTime.getMean(),
                    motionFlowStatistics.processingTime.getStdDev()}));
            gl.glPopMatrix();
        }
        
        if (measureAccuracy) {
            gl.glPushMatrix();
            gl.glRasterPos2i(240,10);
            chip.getCanvas().getGlut().glutBitmapString(GLUT.BITMAP_HELVETICA_18, 
                String.format("%4.2f +/- %5.2f pixel/s", new Object[]{
                    motionFlowStatistics.endpointErrorAbs.getMean(),
                    motionFlowStatistics.endpointErrorAbs.getStdDev()}));
            gl.glPopMatrix();
            gl.glPushMatrix();
            gl.glRasterPos2i(240,20);
            chip.getCanvas().getGlut().glutBitmapString(GLUT.BITMAP_HELVETICA_18, 
                String.format("%4.2f +/- %5.2f °", new Object[]{
                    motionFlowStatistics.angularError.getMean(),
                    motionFlowStatistics.angularError.getStdDev()}));
            gl.glPopMatrix();
        }
    }
    
    synchronized void setupFilter(EventPacket in) {
        addListeners(chip);
        inItr = in.iterator();
        outItr = dirPacket.outputIterator();
        subsampledPixelIsSet = new boolean[subSizeX][subSizeY];
        countIn = 0;
        countOut = 0;
        if (measureProcessingTime) motionFlowStatistics.processingTime.startTime = System.nanoTime();
    }
    
    /**
     * @return true if ...
     * 1) the event lies outside the chip.
     * 2) the event's subsampled address falls on a pixel location that has 
     *    already had an event within this packet. We don't want to process or 
     *    render it. Important: This prevents events to acccumulate at the same
     *    pixel location, which is an essential part of the Lucas-Kanade method.
     *    Therefore, subsampling should be avoided in LucasKanadeFlow when the
     *    goal is to optimize accuracy.
     * @param d equals the spatial search distance plus some extra spacing needed
     *        for applying finite differences to calculate gradients.
     */
    synchronized boolean isInvalidAddress(int d) {
        if (x >= d && y >= d && x < subSizeX - d && y < subSizeY - d) {
            if (subSampleShift > 0 && !subsampledPixelIsSet[x][y])
                subsampledPixelIsSet[x][y] = true;
            return false;
        } return true;
    }
     
    // Returns true if the event lies outside certain spatial bounds.
    synchronized boolean xyFilter() {
        return x < xMin || x >= xMax || y < yMin || y >= yMax;
    }
    
    synchronized boolean updateTimesmap() {
        lastTs = lastTimesMap[x][y][type];
        lastTimesMap[x][y][type] = ts;
        if (ts < lastTs) resetFilter(); // For NonMonotonicTimeException.
        return ts > lastTs + refractoryPeriodUs;
    }
   
    synchronized void extractEventInfo(Object ein) {
        e = (PolarityEvent) ein;
        x = e.getX() >> subSampleShift;
        y = e.getY() >> subSampleShift;
        ts = e.getTimestamp();
        type = e.getPolarity() == PolarityEvent.Polarity.Off ? 0 : 1;
    }
               
    synchronized void writeOutputEvent() {
        // Copy the input event to a new output event and add the computed optical flow properties
        eout = (ApsDvsMotionOrientationEvent) outItr.nextOutput();
        eout.copyFrom(e);
        eout.x = (short) (x << subSampleShift);
        eout.y = (short) (y << subSampleShift);
        eout.velocity.x = vx;
        eout.velocity.y = vy;
        eout.speed = v;
        eout.hasDirection = v!=0;
        if (v != 0) countOut++;
        if (showGlobalEnabled) 
            motionFlowStatistics.globalMotion.update(vx,vy,v,eout.x,eout.y);
    }
        
    synchronized boolean accuracyTests() {
        // 1.) Filter out events with speed high above average.
        // 2.) Filter out events whose velocity deviates from IMU estimate by a 
        // certain degree.
        return speedControlEnabled && isSpeeder() || discardOutliersEnabled && 
            Math.abs(motionFlowStatistics.angularError.calculateError(vx,vy,v,vxGT,vyGT,vGT))
            > epsilon;
    }
             
    // <editor-fold defaultstate="collapsed" desc="Speed Control">
    protected boolean isSpeeder() {
        // Discard events if velocity is too far above average
        avgSpeed = (1-speedMixingFactor)*avgSpeed + speedMixingFactor*v;
        return v > avgSpeed*excessSpeedRejectFactor;
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="IMUCalibration Start and Reset buttons">
    synchronized public void doStartIMUCalibration() {
        imuFlowEstimator.calibrating = true;
        imuFlowEstimator.panCalibrator.reset();
        imuFlowEstimator.tiltCalibrator.reset();
        imuFlowEstimator.rollCalibrator.reset();
        log.info("IMU calibration started");
    }

    synchronized public void doResetIMUCalibration() {
        imuFlowEstimator.panOffset = 0;
        imuFlowEstimator.tiltOffset = 0;
        imuFlowEstimator.rollOffset = 0;
        log.info("IMU calibration erased");
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="Logging trigger button">
    synchronized public void doTriggerLogging() {
        if (!imuFlowEstimator.isCalibrationSet()) log.info("IMU has not been calibrated yet!");
        log.info(motionFlowStatistics.toString());
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="getter/setter for --speedControlEnabled--">
    public boolean isSpeedControlEnabled() {return speedControlEnabled;}

    public void setSpeedControlEnabled(boolean speedControlEnabled) {
        support.firePropertyChange("speedControlEnabled",this.speedControlEnabled,speedControlEnabled);
        this.speedControlEnabled = speedControlEnabled;
        putBoolean("speedControlEnabled",speedControlEnabled);
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="getter/setter for --speedControl_speedMixingFactor--">
    public float getSpeedControl_SpeedMixingFactor() {return speedMixingFactor;}

    public void setSpeedControl_SpeedMixingFactor(float speedMixingFactor) {
        if(speedMixingFactor > 1) {
            speedMixingFactor=1;
        } else if(speedMixingFactor < Float.MIN_VALUE) {
            speedMixingFactor = Float.MIN_VALUE;
        }
        this.speedMixingFactor = speedMixingFactor;
        putFloat("speedMixingFactor",speedMixingFactor);
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="getter/setter for --speedControl_excessSpeedRejectFactor--">
    public float getSpeedControl_ExcessSpeedRejectFactor() {return excessSpeedRejectFactor;}

    public void setSpeedControl_ExcessSpeedRejectFactor(float excessSpeedRejectFactor) {
        this.excessSpeedRejectFactor = excessSpeedRejectFactor;
        putFloat("excessSpeedRejectFactor",excessSpeedRejectFactor);
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="getter/setter for --epsilon--">
    public float getEpsilon() {return epsilon;}
    
    synchronized public void setEpsilon(float epsilon) {
        if(epsilon > 180) epsilon = 180;
        this.epsilon = epsilon;
        putFloat("epsilon",epsilon);
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="getter/setter for --discardOutliersEnabled--">
    public boolean getDiscardOutliersEnabled() {return this.discardOutliersEnabled;}

    public void setDiscardOutliersEnabled(final boolean discardOutliersEnabled) {
        support.firePropertyChange("discardOutliersEnabled",this.discardOutliersEnabled,discardOutliersEnabled);
        this.discardOutliersEnabled = discardOutliersEnabled;
        putBoolean("discardOutliersEnabled",discardOutliersEnabled);
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="getter/setter for --loggingFolder--">
    public String getLoggingFolder() {return loggingFolder;}

    private void setLoggingFolder(String loggingFolder) {
        getSupport().firePropertyChange("loggingFolder", this.loggingFolder, loggingFolder);
        this.loggingFolder = loggingFolder;
        getPrefs().put("DataLogger.loggingFolder", loggingFolder);
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="getter/setter for --measureAccuracy--">
    public synchronized boolean isMeasureAccuracy() {return measureAccuracy;}

    public synchronized void setMeasureAccuracy(boolean measureAccuracy) {
        support.firePropertyChange("measureAccuracy",this.measureAccuracy,measureAccuracy);
        this.measureAccuracy = measureAccuracy;
        putBoolean("measureAccuracy",measureAccuracy);
        if (measureAccuracy) {
            //setMeasureProcessingTime(false);
            resetFilter();
        }
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="getter/setter for --measureProcessingTime--">
    public synchronized boolean isMeasureProcessingTime() {return measureProcessingTime;}

    public synchronized void setMeasureProcessingTime(boolean measureProcessingTime) {
        if (measureProcessingTime) {
            setRefractoryPeriodUs(1);
            //support.firePropertyChange("measureAccuracy",this.measureAccuracy,false);
            //this.measureAccuracy = false;
            resetFilter();
            this.measureProcessingTime = measureProcessingTime;
            //motionFlowStatistics.processingTime.openLog(loggingFolder);
        } //else motionFlowStatistics.processingTime.closeLog(loggingFolder,searchDistance);
        support.firePropertyChange("measureProcessingTime",this.measureProcessingTime,measureProcessingTime);
        this.measureProcessingTime = measureProcessingTime;
        putBoolean("measureProcessingTime",measureProcessingTime);
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="getter/setter for --showGlobalEnabled--">
    public boolean isShowGlobalEnabled() {return showGlobalEnabled;}

    public void setShowGlobalEnabled(boolean showGlobalEnabled) {
        this.showGlobalEnabled = showGlobalEnabled;
        putBoolean("showGlobalEnabled",showGlobalEnabled);
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="getter/setter for --showVectorsEnabled--">
    public boolean isShowVectorsEnabled() {return showVectorsEnabled;}

    public void setShowVectorsEnabled(boolean showVectorsEnabled) {
        this.showVectorsEnabled = showVectorsEnabled;
        putBoolean("showVectorsEnabled",showVectorsEnabled);
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="getter/setter for --ppsScale--">
    public float getPpsScale() {return ppsScale;}

    /** 
     * scale for drawn motion vectors, pixels per second per pixel
     * @param ppsScale 
     */
    public void setPpsScale(float ppsScale) {
        this.ppsScale = ppsScale;
        putFloat("ppsScale",ppsScale);
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="getter/setter for --showRawInputEnable--">
    public boolean isShowRawInputEnabled() {return showRawInputEnabled;}

    public void setShowRawInputEnabled(boolean showRawInputEnabled) {
        this.showRawInputEnabled = showRawInputEnabled;
        putBoolean("showRawInputEnabled",showRawInputEnabled);
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="getter/setter for --subSampleShift--">
    public int getSubSampleShift() {return subSampleShift;}

    /** Sets the number of spatial bits to subsample events times by.
     * Setting this equal to 1, for example, subsamples into an event time map
     * with halved spatial resolution, aggregating over more space at coarser
     * resolution but increasing the search range by a factor of two at no additional cost
     * @param subSampleShift the number of bits, 0 means no subsampling 
     */
    synchronized public void setSubSampleShift(int subSampleShift) {
        if(subSampleShift < 0) subSampleShift = 0;
        else if(subSampleShift > 4) subSampleShift = 4;
        this.subSampleShift = subSampleShift;
        putInt("subSampleShift",subSampleShift);
        subSizeX = sizex >> subSampleShift;
        subSizeY = sizey >> subSampleShift;
        allocateMap();
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="getter/setter for --refractoryPeriodUs--">
    public int getRefractoryPeriodUs() {return refractoryPeriodUs;}

    public void setRefractoryPeriodUs(int refractoryPeriodUs) {
        support.firePropertyChange("refractoryPeriodUs",this.refractoryPeriodUs,refractoryPeriodUs);
        this.refractoryPeriodUs = refractoryPeriodUs;
        putInt("refractoryPeriodUs", refractoryPeriodUs);
    }
    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="getter/setter for --xMin--">
    public int getXMin() {return xMin;}

    public void setXMin(int xMin) {
        if (xMin > xMax) xMin = xMax;
        this.xMin = xMin;
        putInt("xMin",xMin);
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="getter/setter for --xMax--">
    public int getXMax() {return xMax;}

    public void setXMax(int xMax) {
        if (xMax > subSizeX) xMax = subSizeX;
        this.xMax = xMax;
        putInt("xMax",xMax);
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="getter/setter for --yMin--">
    public int getYMin() {return yMin;}

    public void setYMin(int yMin) {
        if (yMin > yMax) yMin = yMax;
        this.yMin = yMin;
        putInt("yMin",yMin);
    }
    // </editor-fold>
    
    // <editor-fold defaultstate="collapsed" desc="getter/setter for --yMax--">
    public int getYMax() {return yMax;}

    public void setYMax(int yMax) {
        if (yMax > subSizeY) yMax = subSizeY;
        this.yMax = yMax;
        putInt("yMax",yMax);
    }
    // </editor-fold>
}