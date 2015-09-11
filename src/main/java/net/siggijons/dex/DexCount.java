package net.siggijons.dex;

import com.android.dexdeps.DexData;
import com.android.dexdeps.MethodRef;
import com.android.dexdeps.Output;
import com.beust.jcommander.internal.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang.ArrayUtils;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class DexCount {

    public static class Node {
        public String name;
        public int count = 0;
        public Map<String, Node> children = Maps.newHashMap();

        public Node(String name) {
            this.name = name;
        }
    }

    private static class Report {
        public int totalMethods;
        public Map<String, Integer> dexFileMethods = Maps.newLinkedHashMap();
        public Node root;

        public List<List<Object>> generateTreeMapReportData()
        {
            List<List<Object>> data = Lists.newArrayList();
            data.add(Arrays.<Object>asList("Package", "Parent", "Methods"));
            data.addAll(nodeTreeMapData(root, null));
            return data;
        }

        public List<List<Object>> generateGaugesData()
        {
            List<List<Object>> data = Lists.newArrayList();
            data.add(Arrays.<Object>asList("File", "Methods"));
            for (Map.Entry<String, Integer> entry : dexFileMethods.entrySet()) {
                data.add(Arrays.<Object>asList(entry.getKey(), entry.getValue()));
            }
            data.add(Arrays.<Object>asList("Total", totalMethods));
            return data;
        }

        private List<List<Object>> nodeTreeMapData(Node node, String parent) {

            List<List<Object>> rows = new ArrayList<List<Object>>();

            //Concat package names, without root
            String nodeName = parent != null && !parent.equals("<root>")
                    ? parent + "." + node.name
                    : node.name;

            rows.add(Arrays.<Object>asList(nodeName, parent, node.count));

            for (Node child : node.children.values()) {
                rows.addAll(nodeTreeMapData(child, nodeName));
            }

            return rows;
        }
    }

    public Report countMethods(String fileName) throws IOException {
        Map<String, RandomAccessFile> dexFiles = openInputFiles(fileName);
        return createReport(dexFiles);
    }

    protected Report createReport(Map<String, RandomAccessFile> dexFiles) throws IOException {
        Report report = new Report();
        report.root = new Node("<root>");

        for (Map.Entry<String, RandomAccessFile> dexFileEntry : dexFiles.entrySet()) {
            DexData dexData = loadDexData(dexFileEntry.getValue());

            MethodRef[] methodRefs = dexData.getMethodRefs();
            report.totalMethods += methodRefs.length;
            report.dexFileMethods.put(dexFileEntry.getKey(), methodRefs.length);

            for (MethodRef methodRef : methodRefs) {
                String packageName = Output.packageNameOnly(methodRef.getDeclClassName());
                String[] packageNamePieces = splitPackageName(packageName);

                countMethod(packageNamePieces, report.root);
            }
        }
        return report;
    }

    private String[] splitPackageName(String packageName) {
        String[] split = packageName.split("\\.");
        for (int i = 0; i < split.length; i++) {
            if (split[i].isEmpty())
            {
                split[i] = "<default>";
            }
        }
        return split;
    }

    protected void countMethod(String[] packageName, Node node)
    {
        node.count++;
        if (packageName.length == 0) return;
        Node child = node.children.get(packageName[0]);
        if (child == null)
        {
            child = new Node(packageName[0]);
            node.children.put(packageName[0], child);
        }

        String[] tail = (String[]) ArrayUtils.subarray(packageName, 1, packageName.length);
        countMethod(tail, child);
    }

    protected DexData loadDexData(RandomAccessFile dexFile) throws IOException {
        DexData dexData = new DexData(dexFile);
        dexData.load();
        return dexData;
    }

    Map<String, RandomAccessFile> openInputFiles(String fileName) throws IOException {
        Map<String, RandomAccessFile> dexFiles = Maps.newLinkedHashMap();

        openInputFileAsZip(fileName, dexFiles);
        if (dexFiles.size() == 0) {
            File inputFile = new File(fileName);
            RandomAccessFile dexFile = new RandomAccessFile(inputFile, "r");
            dexFiles.put(inputFile.getName(), dexFile);
        }

        return dexFiles;
    }

    /**
     * Tries to open an input file as a Zip archive (jar/apk) with a
     * "classes.dex" inside.
     */
    void openInputFileAsZip(String fileName, Map<String, RandomAccessFile> dexFiles) throws IOException {
        ZipFile zipFile;

        // Try it as a zip file.
        try {
            zipFile = new ZipFile(fileName);
        } catch (FileNotFoundException fnfe) {
            // not found, no point in retrying as non-zip.
            System.err.println("Unable to open '" + fileName + "': " +
                    fnfe.getMessage());
            throw fnfe;
        } catch (ZipException ze) {
            // not a zip
            return;
        }

        // Open and add all files matching "*.dex" in the zip file.
        for (ZipEntry entry : Collections.list(zipFile.entries())) {
            if (entry.getName().matches(".*\\.dex")) {
                dexFiles.put(entry.getName(), openDexFile(zipFile, entry));
            }
        }

        zipFile.close();
    }

    RandomAccessFile openDexFile(ZipFile zipFile, ZipEntry entry) throws IOException {
        // We know it's a zip; see if there's anything useful inside.  A
        // failure here results in some type of IOException (of which
        // ZipException is a subclass).
        InputStream zis = zipFile.getInputStream(entry);

        // Create a temp file to hold the DEX data, open it, and delete it
        // to ensure it doesn't hang around if we fail.
        File tempFile = File.createTempFile("dexdeps", ".dex");
        RandomAccessFile dexFile = new RandomAccessFile(tempFile, "rw");
        tempFile.delete();

        // Copy all data from input stream to output file.
        byte copyBuf[] = new byte[32768];
        int actual;

        while (true) {
            actual = zis.read(copyBuf);
            if (actual == -1)
                break;

            dexFile.write(copyBuf, 0, actual);
        }

        dexFile.seek(0);

        return dexFile;
    }

}
