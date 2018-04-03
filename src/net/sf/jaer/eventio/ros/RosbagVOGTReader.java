/*
 * Copyright (C) 2018 minliu.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301  USA
 */
package net.sf.jaer.eventio.ros;

import com.github.swrirobotics.bags.reader.exceptions.UninitializedFieldException;
import com.github.swrirobotics.bags.reader.messages.serialization.ArrayType;
import com.github.swrirobotics.bags.reader.messages.serialization.Float64Type;
import com.github.swrirobotics.bags.reader.messages.serialization.MessageType;
import com.github.swrirobotics.bags.reader.messages.serialization.TimeType;
import com.github.swrirobotics.bags.reader.messages.serialization.UInt32Type;
import com.github.swrirobotics.bags.reader.messages.serialization.UInt8Type;

import com.jogamp.opengl.GLAutoDrawable;
import java.nio.ByteOrder;
import java.sql.Timestamp;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.jaer.Description;
import net.sf.jaer.DevelopmentStatus;
import net.sf.jaer.chip.AEChip;
import static net.sf.jaer.eventprocessing.EventFilter.log;
import net.sf.jaer.graphics.FrameAnnotater;

import org.opencv.core.Point3;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import org.apache.commons.math3.complex.Quaternion;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;

import org.jblas.DoubleMatrix;
import org.jblas.MatrixFunctions;
import org.jblas.Solve;
import org.opencv.core.Core;
import nu.pattern.OpenCV;



/** Rosbag reader for MSVEC VO Groundtruth which is packed in rosbag format. 
 *  Dataset paper: 
 *  Zhu, A. Z., Thakur, D., Ozaslan, T., Pfrommer, B., Kumar, V., & Daniilidis, K. (2018). 
 *  The Multi Vehicle Stereo Event Camera Dataset: An Event Camera Dataset for 3D Perception. arXiv preprint arXiv:1801.10202.
 * 
 * @author minliu
 */
@Description("Extract pose message in se3 representation and parse depth message.")
@DevelopmentStatus(DevelopmentStatus.Status.Experimental)
public class RosbagVOGTReader extends RosbagMessageDisplayer implements FrameAnnotater {

    private DoubleMatrix last_rotation = DoubleMatrix.eye(3);
    private DoubleMatrix current_rotation = DoubleMatrix.eye(3);
    private DoubleMatrix last_position = DoubleMatrix.zeros(3, 1);
    private DoubleMatrix current_position = DoubleMatrix.zeros(3, 1);
    
    static {        
        try {
            OpenCV.loadShared();   // search opencv native library with nu.pattern package.
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Native code library failed to load.\n" + e);
        }
    }
//    private ArrayList<Se3Info> se3InfoList;
//    private ArrayList< float[] > depthList;
    private float[] currentDepth_image;
    private long currentPose_seq_num;
    private Timestamp currentPose_ts;
    private DoubleMatrix currentPoseSe3;
    private Timestamp currentDepth_ts;
    private Timestamp lastPose_ts;
    
//    private long firstAbsoluteTs;
//    private boolean firstTimestampWasRead;

    public RosbagVOGTReader(AEChip chip) {
        super(chip);
        ArrayList<String> topics = new ArrayList();
        topics.add("/davis/left/pose");
        topics.add("/davis/left/depth_image_raw");
        addTopics(topics);
        lastPose_ts = new Timestamp(0);
        currentPose_ts = new Timestamp(0);
        currentDepth_ts = new Timestamp(0);
//        se3InfoList = new ArrayList<Se3Info>();
//        depthList = new ArrayList<float[]>();
//        currentSe3Info = new Se3Info();
    }

    @Override
    protected void parseMessage(RosbagFileInputStream.MessageWithIndex message) {
        String pkg = message.messageType.getPackage();

//        if (chip.getAeInputStream() instanceof RosbagFileInputStream) {
//            if (!firstTimestampWasRead) {
//                firstAbsoluteTs = ((RosbagFileInputStream)(chip.getAeInputStream())).getFirstTimestampUsAbsolute();
//                firstTimestampWasRead = true;
//            }
//        }
        
        if (pkg.equalsIgnoreCase("sensor_msgs")) {
            ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;
            try {
                byteOrder = (message.messageType.<UInt8Type>getField("is_bigendian").getValue() == 0) ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            } catch (UninitializedFieldException ex) {
                Logger.getLogger(RosbagVOGTReader.class.getName()).log(Level.SEVERE, null, ex);
            }
            message.messageType.<ArrayType>getField("data").setOrder(byteOrder);
            
            try {
                currentDepth_ts = message.messageType.<MessageType>getField("header").<TimeType>getField("stamp").getValue();
            } catch (UninitializedFieldException ex) {
                Logger.getLogger(RosbagVOGTReader.class.getName()).log(Level.SEVERE, null, ex);
            }
            currentDepth_image = message.messageType.<ArrayType>getField("data").getAsFloats();
//            depthList.add(current_depth_image);
        }
        
        if (pkg.equalsIgnoreCase("geometry_msgs")) {

            try {
                // Extract position information.
                double x_pos = message.messageType.<MessageType>getField("pose").<MessageType>getField("position")
                        .<Float64Type>getField("x").getValue();
                double y_pos = message.messageType.<MessageType>getField("pose").<MessageType>getField("position")
                        .<Float64Type>getField("y").getValue();
                double z_pos = message.messageType.<MessageType>getField("pose").<MessageType>getField("position")
                        .<Float64Type>getField("z").getValue();  
                // Extract orientation information
                double x_quat = message.messageType.<MessageType>getField("pose").<MessageType>getField("orientation")
                        .<Float64Type>getField("x").getValue();
                double y_quat = message.messageType.<MessageType>getField("pose").<MessageType>getField("orientation")
                        .<Float64Type>getField("y").getValue();
                double z_quat = message.messageType.<MessageType>getField("pose").<MessageType>getField("orientation")
                        .<Float64Type>getField("z").getValue();  
                double w_quat = message.messageType.<MessageType>getField("pose").<MessageType>getField("orientation")
                        .<Float64Type>getField("w").getValue();   

                currentPose_seq_num = message.messageType.<MessageType>getField("header").<UInt32Type>getField("seq").getValue();
                lastPose_ts = currentPose_ts;
                currentPose_ts = message.messageType.<MessageType>getField("header").<TimeType>getField("stamp").getValue();
    //            se3Info.se3_ts_relative_us = se3Info.se3_ts.getTime()*1000+(long)(se3Info.se3_ts.getNanos()/1000) - firstAbsoluteTs;
//                log.info("\nPose: seq: " + currentPose_seq_num + "\n" + "Pose: timestamp: " + currentPose_ts + "\t" + 
//                        (currentPose_ts.getTime() + currentPose_ts.getNanos()/1.e6 - (int)(currentPose_ts.getNanos()/1.e6)));

                last_position = current_position;
                current_position = new DoubleMatrix(new double[]{x_pos, y_pos, z_pos});

                // Construct rotation matrix using Quaternion
                Rotation rota = new Rotation(w_quat, x_quat, y_quat, z_quat, false);
                Quaternion quat = new Quaternion(w_quat, x_quat, y_quat, z_quat);

                // Convert it to a matrix type that could use log function from jblas library.
                last_rotation = current_rotation.dup();
                current_rotation = new DoubleMatrix(rota.getMatrix());
                 /*
                    Jblas and apache use a different matrix order, the transpose is required here.
                    The reason why we don't use jblas only for matrix calculation is jblas doesn't
                    support Quaternion            
                */
                current_rotation = current_rotation.transpose(); 
//                log.info("\nPose: position: " + current_position + "\n" + "Pose: orientation: " + current_rotation 
//                        + "\n" + "Pose: quaternion: " + quat);

                DoubleMatrix R = current_rotation.mmul(last_rotation.transpose());
                DoubleMatrix trans = current_position.sub(R.mmul(last_position));          

                currentPoseSe3 = SE3Tose3(R, trans);   
//                log.info("The se3 vector is: " + currentSe3Info.se3_data + "\n");

//                se3InfoList.add(currentSe3Info);
                /* 
                Following code is just for testing the matrix exp function in jblas.                        
                Test rotation vector is:
                {-2.100418,-2.167796,0.273330}

                The result rotation matrix should be:
                {0, -0.273330, -2.167796}, 
                {0.273330, 0, 2.100418}, 
                {2.16779, -2.100418, 0}}


                DoubleMatrix lie_group_test = MatrixFunctions.expm(new DoubleMatrix(new double[][]{{0, -0.273330, -2.167796}, 
                                                                                                {0.273330, 0, 2.100418}, 
                                                                                                {2.16779, -2.100418, 0}}
                ));
                log.info("The skew symmetric matrix is:" + lie_group_test);
                */
            } catch (UninitializedFieldException ex) {
                Logger.getLogger(SlasherRosbagDisplay.class.getName()).log(Level.SEVERE, null, ex);
            } 
        }   
    }

//    public ArrayList<Se3Info> getSe3InfoList() {
//        return se3InfoList;
//    }
//
//    public ArrayList<float[]> getDepthList() {
//        return depthList;
//    }

    public float[] getCurrent_depth_image() {
        return currentDepth_image;
    }   

    public long getCurrentPose_seq_num() {
        return currentPose_seq_num;
    }

    public void setCurrentPose_seq_num(long currentPose_seq_num) {
        this.currentPose_seq_num = currentPose_seq_num;
    }

    public Timestamp getCurrentPose_ts() {
        return currentPose_ts;
    }

    public void setCurrentPose_ts(Timestamp currentPose_ts) {
        this.currentPose_ts = currentPose_ts;
    }

    public DoubleMatrix getCurrentPoseSe3() {
        return currentPoseSe3;
    }

    public void setCurrentPoseSe3(DoubleMatrix currentPoseSe3) {
        this.currentPoseSe3 = currentPoseSe3;
    }

    public Timestamp getLastPose_ts() {
        return lastPose_ts;
    }    
    
    public Timestamp getCurrentDepth_ts() {
        return currentDepth_ts;
    }   
    
    
//    public Se3Info getCurrentSe3Info() {
//        return currentSe3Info;
//    }    
    
    @Override
    public void annotate(GLAutoDrawable drawable) {
    }    
    
    public DoubleMatrix SE3Tose3(DoubleMatrix R, DoubleMatrix trans) {
        // Calculate rotation's lie algebra w.
        Mat src = new Mat(3, 3, CvType.CV_64FC1);            
        src.put(0, 0, R.data);
        Mat w = new Mat();
        Mat jocobian = new Mat();
        Calib3d.Rodrigues(src, w, jocobian);
//            log.info("The Rodrigues vector is: " + w.dump());

        double[] w_data = new double[3];
        w.get(0, 0, w_data);
        DoubleMatrix ww = new  DoubleMatrix(1, 3, w_data);
        double theta = ww.norm2();
        DoubleMatrix W = new DoubleMatrix(new double[][]{{0, -ww.get(2), ww.get(1)}, 
                                                         {ww.get(2), 0, -ww.get(0)}, 
                                                         {-ww.get(1), ww.get(0), 0}});

        DoubleMatrix jaccobLieAlg = DoubleMatrix.eye(3);   // Translational part of lie algebra
        if (theta != 0) {
            double A = Math.sin(theta)/theta;
            double B = (1 - Math.cos(theta))/(theta * theta);
            jaccobLieAlg = DoubleMatrix.eye(3).sub(W.mul(0.5)).add(W.mmul(W).mul(1/(theta*theta) * (1 - (0.5*A/B))));
        }
        DoubleMatrix v = jaccobLieAlg.mmul(trans);
        return new DoubleMatrix(new double[]{v.get(0), v.get(1), v.get(2),ww.get(0), ww.get(1),ww.get(2)});
    }
    
//    public class Se3Info {
//        public long pose_seq_num;
//        public Timestamp se3_ts;
//        public DoubleMatrix se3_data;
//        public long se3_ts_relative_us;
//
//        public Se3Info() {
//            this.se3_ts = new Timestamp(0);
//            this.se3_data = DoubleMatrix.zeros(1, 6);
//            this.pose_seq_num = 0;
//        }
//        
//    }
    
}