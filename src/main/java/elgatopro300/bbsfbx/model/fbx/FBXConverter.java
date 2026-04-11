package elgatopro300.bbsfbx.model.fbx;

import mchorse.bbs_mod.bobj.BOBJAction;
import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJData;
import mchorse.bbs_mod.bobj.BOBJLoader.BOBJMesh;
import mchorse.bbs_mod.bobj.BOBJLoader.Face;
import mchorse.bbs_mod.bobj.BOBJLoader.IndexGroup;
import mchorse.bbs_mod.bobj.BOBJLoader.Vertex;
import mchorse.bbs_mod.bobj.BOBJLoader.Weight;

import org.joml.Matrix4f;
import org.joml.Vector2d;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.AIBone;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMatrix4x4;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AINode;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.AIVertexWeight;
import org.lwjgl.assimp.Assimp;


import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FBXConverter class responsible for converting Assimp AIScene structures
 * into BOBJData format.
 */
public class FBXConverter
{
    private static final int FRAMES_PER_SECOND = 20;
    private static final float TARGET_MODEL_HEIGHT = 1.8f;
    private static final float MIN_REASONABLE_HEIGHT = 0.25f;
    private static final float MAX_REASONABLE_HEIGHT = 4.0f;

    /**
     * Converts an Assimp scene into BOBJData.
     */
    public static BOBJData convert(AIScene scene)
    {
        List<Vertex> vertices = new ArrayList<>();
        List<Vector2d> textures = new ArrayList<>();
        List<Vector3f> normals = new ArrayList<>();
        List<BOBJMesh> meshes = new ArrayList<>();
        Map<String, BOBJAction> actions = new HashMap<>();
        Map<String, BOBJArmature> armatures = new HashMap<>();

        FBXMetadata metadata = new FBXMetadata(scene);
        Matrix4f rootCorrection = buildRootCorrection(metadata);
        Map<Integer, Matrix4f> meshTransforms = collectMeshTransforms(scene.mRootNode());

        Map<String, AIBone> skinnedBones = new HashMap<>();
        int numMeshes = scene.mNumMeshes();
        for (int i = 0; i < numMeshes; i++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            int numBones = aiMesh.mNumBones();
            for (int j = 0; j < numBones; j++)
            {
                AIBone aiBone = AIBone.create(aiMesh.mBones().get(j));
                skinnedBones.putIfAbsent(aiBone.mName().dataString(), aiBone);
            }
        }

        BOBJArmature globalArmature = new BOBJArmature("Armature");
        armatures.put(globalArmature.name, globalArmature);

        float[] globalScale = {resolveMetadataScale(metadata)};
        Set<String> neededNodes = new HashSet<>();

        if (!skinnedBones.isEmpty())
        {
            markNeededNodes(scene.mRootNode(), skinnedBones.keySet(), neededNodes);

            float s = findFirstScale(scene.mRootNode(), neededNodes, skinnedBones);
            if (s > 0) globalScale[0] *= s;
        }
        else
        {
            BOBJBone root = new BOBJBone(0, "root", "", new Matrix4f());
            globalArmature.addBone(root);
        }

        Bounds initialBounds = computeBounds(scene, rootCorrection, globalScale[0], meshTransforms);
        float initialHeight = initialBounds.height();
        if (initialHeight > 0 && (initialHeight < MIN_REASONABLE_HEIGHT || initialHeight > MAX_REASONABLE_HEIGHT))
        {
            globalScale[0] *= TARGET_MODEL_HEIGHT / initialHeight;
        }

        Bounds finalBounds = computeBounds(scene, rootCorrection, globalScale[0], meshTransforms);

        float offsetX = 0;
        float offsetY = 0;
        float offsetZ = 0;
        if (finalBounds.hasHorizontalBounds())
        {
            offsetX = -(finalBounds.minX + finalBounds.maxX) / 2.0f;
            offsetZ = -(finalBounds.minZ + finalBounds.maxZ) / 2.0f;
        }
        if (finalBounds.minY != Float.MAX_VALUE && Math.abs(finalBounds.minY) > 0.001f)
        {
            offsetY = -finalBounds.minY;
        }

        if (!skinnedBones.isEmpty())
        {
            Matrix4f initialGlobal = new Matrix4f().translate(offsetX, offsetY, offsetZ);
            processNodes(scene.mRootNode(), "", initialGlobal, globalArmature, skinnedBones, neededNodes, globalScale, rootCorrection, offsetX, offsetY, offsetZ);
        }

        for (int i = 0; i < numMeshes; i++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            processMesh(scene, aiMesh, i, vertices, textures, normals, meshes, globalArmature, globalScale[0], rootCorrection, offsetX, offsetY, offsetZ, meshTransforms);
        }


        for (Vertex vertex : vertices)
        {
            if (vertex.weights.isEmpty())
            {
                if (!globalArmature.orderedBones.isEmpty())
                {
                    vertex.weights.add(new Weight(globalArmature.orderedBones.get(0).name, 1.0f));
                }
            }
            else
            {
                vertex.eliminateTinyWeights();

                float sum = 0;
                for (Weight w : vertex.weights)
                {
                    sum += w.factor;
                }

                if (sum > 0 && Math.abs(sum - 1.0f) > 0.001f)
                {
                    for (Weight w : vertex.weights)
                    {
                        w.factor /= sum;
                    }
                }
            }
        }

        return new BOBJData(vertices, textures, normals, meshes, actions, armatures);
    }

    private static float findFirstScale(AINode node, Set<String> neededNodes, Map<String, AIBone> skinnedBones)
    {
        String name = node.mName().dataString();
        if (neededNodes.contains(name) && skinnedBones.containsKey(name))
        {
            Matrix4f ibm = toMatrix4f(skinnedBones.get(name).mOffsetMatrix());
            Matrix4f bindMatrix = ibm.invert();
            Vector3f scale = new Vector3f();
            bindMatrix.getScale(scale);
            return scale.x;
        }

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            float scale = findFirstScale(AINode.create(children.get(i)), neededNodes, skinnedBones);
            if (scale != -1) return scale;
        }

        return -1;
    }

    /**
     * Recursively checks if a node or its children are referenced in the skinnedBones map.
     * Populates neededNodes with names of nodes that should be part of the armature.
     */
    private static boolean markNeededNodes(AINode node, Set<String> skinnedBones, Set<String> neededNodes)
    {
        String name = node.mName().dataString();
        boolean needed = skinnedBones.contains(name);

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            AINode child = AINode.create(children.get(i));
            if (markNeededNodes(child, skinnedBones, neededNodes))
            {
                needed = true;
            }
        }

        if (needed)
        {
            neededNodes.add(name);
        }

        return needed;
    }

    /**
     * Recursively processes nodes to build the bone hierarchy.
     * Calculates global transformations and applies corrections.
     */
    private static void processNodes(AINode node, String parentName, Matrix4f parentGlobal, BOBJArmature armature, Map<String, AIBone> skinnedBones, Set<String> neededNodes, float[] globalScale, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ)
    {
        String name = node.mName().dataString();
        Matrix4f local = toMatrix4f(node.mTransformation());
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);

        String nextParent = parentName;
        boolean skip = name.equals("RootNode") || name.equals("Armature");

        if (neededNodes.contains(name) && !skip)
        {
            Matrix4f boneMat;
            if (skinnedBones.containsKey(name))
            {
                Matrix4f offset = toMatrix4f(skinnedBones.get(name).mOffsetMatrix());
                Matrix4f invCorrection = new Matrix4f(rootCorrection).invert();
                offset.mul(invCorrection);
                boneMat = offset.invert();

                boneMat.m30(boneMat.m30() * globalScale[0]);
                boneMat.m31(boneMat.m31() * globalScale[0]);
                boneMat.m32(boneMat.m32() * globalScale[0]);

                boneMat.m30(boneMat.m30() + offsetX);
                boneMat.m31(boneMat.m31() + offsetY);
                boneMat.m32(boneMat.m32() + offsetZ);
            }
            else
            {
                boneMat = new Matrix4f(global);
            }

            if (armature.bones.isEmpty())
            {
                if (!skinnedBones.containsKey(name))
                {
                    boneMat.mul(rootCorrection);
                }
            }

            boneMat.normalize3x3();

            BOBJBone bone = new BOBJBone(armature.bones.size(), name, parentName, boneMat);
            armature.addBone(bone);
            nextParent = name;
        }

        int numChildren = node.mNumChildren();
        PointerBuffer children = node.mChildren();

        for (int i = 0; i < numChildren; i++)
        {
            AINode child = AINode.create(children.get(i));
            processNodes(child, nextParent, global, armature, skinnedBones, neededNodes, globalScale, rootCorrection, offsetX, offsetY, offsetZ);
        }
    }

    /**
     * Converts an Assimp mesh to a BOBJMesh.
     * Transforms vertices/normals into the correct coordinate space and extracts weights.
     */
    private static void processMesh(AIScene scene, AIMesh aiMesh, int meshIndex, List<Vertex> vertices, List<Vector2d> textures, List<Vector3f> normals, List<BOBJMesh> meshes, BOBJArmature armature, float scaleFactor, Matrix4f rootCorrection, float offsetX, float offsetY, float offsetZ, Map<Integer, Matrix4f> meshTransforms)
    {
        FBXMesh mesh = new FBXMesh(aiMesh.mName().dataString());
        mesh.armatureName = armature.name;
        mesh.armature = armature;
        Matrix4f meshTransform = meshTransforms.get(meshIndex);
        Matrix4f meshRotationScale = toRotationScale(meshTransform);
        boolean applyNodeTransform = aiMesh.mNumBones() == 0 && meshRotationScale != null;

        int vertexBaseIndex = vertices.size();
        int textureBaseIndex = textures.size();
        int normalBaseIndex = normals.size();

        Vector3f pos = new Vector3f();

        AIVector3D.Buffer aiVertices = aiMesh.mVertices();
        while (aiVertices.remaining() > 0)
        {
            AIVector3D aiVertex = aiVertices.get();

            pos.set(aiVertex.x() * scaleFactor, aiVertex.y() * scaleFactor, aiVertex.z() * scaleFactor);
            if (applyNodeTransform)
            {
                meshRotationScale.transformPosition(pos);
            }

            rootCorrection.transformPosition(pos);

            pos.x += offsetX;
            pos.y += offsetY;
            pos.z += offsetZ;

            vertices.add(new Vertex(pos.x, pos.y, pos.z));
        }

        AIVector3D.Buffer aiNormals = aiMesh.mNormals();
        if (aiNormals != null)
        {
            while (aiNormals.remaining() > 0)
            {
                AIVector3D aiNormal = aiNormals.get();
                Vector3f norm = new Vector3f(aiNormal.x(), aiNormal.y(), aiNormal.z());
                if (applyNodeTransform)
                {
                    meshRotationScale.transformDirection(norm);
                }

                rootCorrection.transformDirection(norm);
                norm.normalize();

                normals.add(norm);
            }
        }
        else
        {
            int count = aiMesh.mNumVertices();
            for (int i = 0; i < count; i++)
            {
                normals.add(new Vector3f(0, 1, 0));
            }
        }

        AIVector3D.Buffer aiTextureCoords = aiMesh.mTextureCoords(0);
        if (aiTextureCoords != null)
        {
            while (aiTextureCoords.remaining() > 0)
            {
                AIVector3D aiTexCoord = aiTextureCoords.get();
                textures.add(new Vector2d(aiTexCoord.x(), aiTexCoord.y()));
            }
        }
        else
        {
            int count = aiMesh.mNumVertices();
            for (int i = 0; i < count; i++)
            {
                textures.add(new Vector2d(0, 0));
            }
        }

        int numFaces = aiMesh.mNumFaces();
        for (int i = 0; i < numFaces; i++)
        {
            IntBuffer faceIndices = aiMesh.mFaces().get(i).mIndices();
            if (faceIndices.remaining() == 3)
            {
                Face face = new Face();
                for (int j = 0; j < 3; j++)
                {
                    int index = faceIndices.get(j);
                    IndexGroup group = new IndexGroup();
                    group.idxPos = vertexBaseIndex + index;
                    group.idxVecNormal = normalBaseIndex + index;
                    group.idxTextCoord = textureBaseIndex + index;
                    face.idxGroups[j] = group;
                }
                mesh.faces.add(face);
            }
        }

        int numBones = aiMesh.mNumBones();
        if (numBones > 0)
        {
            for (int i = 0; i < numBones; i++)
            {
                AIBone aiBone = AIBone.create(aiMesh.mBones().get(i));
                String boneName = aiBone.mName().dataString();

                AIVertexWeight.Buffer aiWeights = aiBone.mWeights();
                while (aiWeights.remaining() > 0)
                {
                    AIVertexWeight aiWeight = aiWeights.get();
                    int vertexId = aiWeight.mVertexId();
                    float weight = aiWeight.mWeight();

                    if (vertexId + vertexBaseIndex < vertices.size())
                    {
                        vertices.get(vertexBaseIndex + vertexId).weights.add(new Weight(boneName, weight));
                    }
                }
            }
        }

        int materialIndex = aiMesh.mMaterialIndex();
        if (materialIndex >= 0 && materialIndex < scene.mNumMaterials())
        {
            AIMaterial material = AIMaterial.create(scene.mMaterials().get(materialIndex));
            AIString path = AIString.calloc();

            if (Assimp.aiGetMaterialTexture(material, Assimp.aiTextureType_DIFFUSE, 0, path, (IntBuffer) null, null, null, null, null, null) == Assimp.aiReturn_SUCCESS)
            {
                String texturePath = path.dataString();

                if (texturePath != null && !texturePath.isEmpty())
                {
                    texturePath = texturePath.replace('\\', '/');
                    int lastSlash = texturePath.lastIndexOf('/');

                    if (lastSlash >= 0)
                    {
                        texturePath = texturePath.substring(lastSlash + 1);
                    }

                    mesh.texture = texturePath;
                }
            }

            path.free();
        }

        meshes.add(mesh);
    }

    /* Need some fixes!
    private static void processAnimation(AIAnimation aiAnimation, Map<String, BOBJAction> actions, float scaleFactor)
    {
        String name = aiAnimation.mName().dataString();
        if (name.isEmpty()) name = "animation";

        BOBJAction action = new BOBJAction(name);
        actions.put(name, action);

        double duration = aiAnimation.mDuration();
        double ticksPerSecond = aiAnimation.mTicksPerSecond();
        if (ticksPerSecond == 0) ticksPerSecond = 24.0;

        int numChannels = aiAnimation.mNumChannels();
        for (int i = 0; i < numChannels; i++)
        {
            AINodeAnim aiNodeAnim = AINodeAnim.create(aiAnimation.mChannels().get(i));
            String nodeName = aiNodeAnim.mNodeName().dataString();

            BOBJGroup group = new BOBJGroup(nodeName);
            action.groups.put(nodeName, group);

            BOBJChannel tx = new BOBJChannel("location.x", 0);
            BOBJChannel ty = new BOBJChannel("location.y", 1);
            BOBJChannel tz = new BOBJChannel("location.z", 2);

            BOBJChannel rx = new BOBJChannel("rotation.x", 3);
            BOBJChannel ry = new BOBJChannel("rotation.y", 4);
            BOBJChannel rz = new BOBJChannel("rotation.z", 5);

            BOBJChannel sx = new BOBJChannel("scale.x", 6);
            BOBJChannel sy = new BOBJChannel("scale.y", 7);
            BOBJChannel sz = new BOBJChannel("scale.z", 8);

            int numPositionKeys = aiNodeAnim.mNumPositionKeys();
            for (int j = 0; j < numPositionKeys; j++)
            {
                AIVectorKey key = aiNodeAnim.mPositionKeys().get(j);
                float time = (float) (key.mTime() / ticksPerSecond * FRAMES_PER_SECOND);
                AIVector3D vec = key.mValue();

                tx.keyframes.add(new BOBJKeyframe(time, vec.x() * scaleFactor));
                ty.keyframes.add(new BOBJKeyframe(time, vec.y() * scaleFactor));
                tz.keyframes.add(new BOBJKeyframe(time, vec.z() * scaleFactor));
            }

            int numRotationKeys = aiNodeAnim.mNumRotationKeys();
            Vector3f euler = new Vector3f();

            for (int j = 0; j < numRotationKeys; j++)
            {
                AIQuatKey key = aiNodeAnim.mRotationKeys().get(j);
                float time = (float) (key.mTime() / ticksPerSecond * FRAMES_PER_SECOND);
                AIQuaternion quat = key.mValue();

                getEulerAngles(quat.x(), quat.y(), quat.z(), quat.w(), euler);

                rx.keyframes.add(new BOBJKeyframe(time, (float) Math.toDegrees(euler.x)));
                ry.keyframes.add(new BOBJKeyframe(time, (float) Math.toDegrees(euler.y)));
                rz.keyframes.add(new BOBJKeyframe(time, (float) Math.toDegrees(euler.z)));
            }

            int numScalingKeys = aiNodeAnim.mNumScalingKeys();
            for (int j = 0; j < numScalingKeys; j++)
            {
                AIVectorKey key = aiNodeAnim.mScalingKeys().get(j);
                float time = (float) (key.mTime() / ticksPerSecond * FRAMES_PER_SECOND);
                sx.keyframes.add(new BOBJKeyframe(time, 1.0f));
                sy.keyframes.add(new BOBJKeyframe(time, 1.0f));
                sz.keyframes.add(new BOBJKeyframe(time, 1.0f));
            }

            if (!tx.keyframes.isEmpty()) group.channels.add(tx);
            if (!ty.keyframes.isEmpty()) group.channels.add(ty);
            if (!tz.keyframes.isEmpty()) group.channels.add(tz);
            if (!rx.keyframes.isEmpty()) group.channels.add(rx);
            if (!ry.keyframes.isEmpty()) group.channels.add(ry);
            if (!rz.keyframes.isEmpty()) group.channels.add(rz);
            if (!sx.keyframes.isEmpty()) group.channels.add(sx);
            if (!sy.keyframes.isEmpty()) group.channels.add(sy);
            if (!sz.keyframes.isEmpty()) group.channels.add(sz);
        }
    }
    */

    private static Matrix4f toMatrix4f(AIMatrix4x4 m)
    {
        return new Matrix4f(
                m.a1(), m.b1(), m.c1(), m.d1(),
                m.a2(), m.b2(), m.c2(), m.d2(),
                m.a3(), m.b3(), m.c3(), m.d3(),
                m.a4(), m.b4(), m.c4(), m.d4()
        );
    }

    private static Matrix4f buildRootCorrection(FBXMetadata metadata)
    {
        Matrix4f correction = new Matrix4f();

        if (metadata.upAxis == 2)
        {
            correction.rotateX((float) Math.toRadians(-90));
        }
        else if (metadata.upAxis == 0)
        {
            correction.rotateZ((float) Math.toRadians(90));
        }

        correction.rotateY((float) Math.PI);

        return correction;
    }

    private static float resolveMetadataScale(FBXMetadata metadata)
    {
        if (metadata.unitScaleFactor <= 0)
        {
            return 1.0f;
        }

        return (float) (metadata.unitScaleFactor / 100.0);
    }

    private static Bounds computeBounds(AIScene scene, Matrix4f rootCorrection, float scale, Map<Integer, Matrix4f> meshTransforms)
    {
        Bounds bounds = new Bounds();
        Vector3f pos = new Vector3f();
        int numMeshes = scene.mNumMeshes();

        for (int i = 0; i < numMeshes; i++)
        {
            AIMesh aiMesh = AIMesh.create(scene.mMeshes().get(i));
            Matrix4f meshTransform = meshTransforms.get(i);
            Matrix4f meshRotationScale = toRotationScale(meshTransform);
            boolean applyNodeTransform = aiMesh.mNumBones() == 0 && meshRotationScale != null;
            AIVector3D.Buffer aiVertices = aiMesh.mVertices();

            while (aiVertices.remaining() > 0)
            {
                AIVector3D v = aiVertices.get();
                pos.set(v.x() * scale, v.y() * scale, v.z() * scale);
                if (applyNodeTransform)
                {
                    meshRotationScale.transformPosition(pos);
                }
                rootCorrection.transformPosition(pos);

                if (pos.y < bounds.minY)
                {
                    bounds.minY = pos.y;
                }
                if (pos.y > bounds.maxY)
                {
                    bounds.maxY = pos.y;
                }
                if (pos.x < bounds.minX)
                {
                    bounds.minX = pos.x;
                }
                if (pos.x > bounds.maxX)
                {
                    bounds.maxX = pos.x;
                }
                if (pos.z < bounds.minZ)
                {
                    bounds.minZ = pos.z;
                }
                if (pos.z > bounds.maxZ)
                {
                    bounds.maxZ = pos.z;
                }
            }
        }

        return bounds;
    }

    private static Map<Integer, Matrix4f> collectMeshTransforms(AINode rootNode)
    {
        Map<Integer, Matrix4f> meshTransforms = new HashMap<>();
        collectMeshTransforms(rootNode, new Matrix4f(), meshTransforms);
        return meshTransforms;
    }

    private static void collectMeshTransforms(AINode node, Matrix4f parentGlobal, Map<Integer, Matrix4f> meshTransforms)
    {
        Matrix4f local = toMatrix4f(node.mTransformation());
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);

        IntBuffer meshIndices = node.mMeshes();
        int numMeshes = node.mNumMeshes();
        for (int i = 0; i < numMeshes; i++)
        {
            int meshIndex = meshIndices.get(i);
            meshTransforms.putIfAbsent(meshIndex, new Matrix4f(global));
        }

        PointerBuffer children = node.mChildren();
        int numChildren = node.mNumChildren();
        for (int i = 0; i < numChildren; i++)
        {
            collectMeshTransforms(AINode.create(children.get(i)), global, meshTransforms);
        }
    }

    private static Matrix4f toRotationScale(Matrix4f matrix)
    {
        if (matrix == null)
        {
            return null;
        }

        Matrix4f result = new Matrix4f(matrix);
        result.m30(0);
        result.m31(0);
        result.m32(0);
        return result;
    }

    /**
     * Converts a Quaternion (x, y, z, w) to Euler angles (roll, pitch, yaw).
     */
    private static void getEulerAngles(float x, float y, float z, float w, Vector3f dest)
    {
        double sinr_cosp = 2 * (w * x + y * z);
        double cosr_cosp = 1 - 2 * (x * x + y * y);
        double roll = Math.atan2(sinr_cosp, cosr_cosp);

        double sinp = 2 * (w * y - z * x);
        double pitch;
        if (Math.abs(sinp) >= 1)
            pitch = Math.copySign(Math.PI / 2, sinp);
        else
            pitch = Math.asin(sinp);

        double siny_cosp = 2 * (w * z + x * y);
        double cosy_cosp = 1 - 2 * (y * y + z * z);
        double yaw = Math.atan2(siny_cosp, cosy_cosp);

        dest.set((float) roll, (float) pitch, (float) yaw);
    }

    /**
     * An extension of BOBJMesh to store the texture filename.
     */
    public static class FBXMesh extends BOBJMesh
    {
        public String texture;

        public FBXMesh(String name)
        {
            super(name);
        }
    }

    private static class Bounds
    {
        private float minY = Float.MAX_VALUE;
        private float maxY = -Float.MAX_VALUE;
        private float minX = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE;
        private float minZ = Float.MAX_VALUE;
        private float maxZ = -Float.MAX_VALUE;

        private float height()
        {
            if (this.minY == Float.MAX_VALUE || this.maxY == -Float.MAX_VALUE)
            {
                return 0;
            }

            return this.maxY - this.minY;
        }

        private boolean hasHorizontalBounds()
        {
            return this.minX != Float.MAX_VALUE && this.maxX != -Float.MAX_VALUE && this.minZ != Float.MAX_VALUE && this.maxZ != -Float.MAX_VALUE;
        }
    }
}
