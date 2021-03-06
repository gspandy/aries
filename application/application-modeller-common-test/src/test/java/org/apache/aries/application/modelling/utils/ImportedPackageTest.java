package org.apache.aries.application.modelling.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.HashMap;
import java.util.Map;

import org.apache.aries.application.InvalidAttributeException;
import org.apache.aries.application.modelling.ImportedPackage;
import org.apache.aries.application.modelling.impl.ImportedPackageImpl;
import org.junit.Test;
import org.osgi.framework.Constants;

public class ImportedPackageTest {

	@Test
	public void testEqualsForIdenticalPackages() throws InvalidAttributeException {
		String packageName = "package.name";
		String version = "1.0.0";
		ImportedPackage package1 = instantiatePackage(packageName, version);
		// I should hope so!
		assertEquals(package1, package1);
	}


	@Test
	public void testEqualsForEqualTrivialPackages() throws InvalidAttributeException {
		String packageName = "package.name";
		String version = "1.0.0";
		ImportedPackage package1 = instantiatePackage(packageName, version);
		ImportedPackage package2 = instantiatePackage(packageName, version);
		assertEquals(package1, package2);
	}

	@Test
	public void testEqualsForEqualPackagesWithVersionRange() throws InvalidAttributeException {
		String packageName = "package.name";
		String version = "[1.0.0, 1.6.3)";
		ImportedPackage package1 = instantiatePackage(packageName, version);
		ImportedPackage package2 = instantiatePackage(packageName, version);
		assertEquals(package1, package2);
	}


	@Test
	public void testEqualsForTrivialPackagesWithDifferentName() throws InvalidAttributeException {
		String version = "1.0.0";
		ImportedPackage package1 = instantiatePackage("package.name", version);
		ImportedPackage package2 = instantiatePackage("other.package.name", version);
		assertFalse("Unexpectedly reported as equal" + package1 + "==" + package2, package1.equals(package2));
	}
	
	@Test
	public void testEqualsForTrivialPackagesWithDifferentVersion() throws InvalidAttributeException {
		String packageName = "package.name";
		ImportedPackage package1 = instantiatePackage(packageName, "1.0.0");
		ImportedPackage package2 = instantiatePackage(packageName, "1.0.1");
		assertFalse("Unexpectedly reported as equal" + package1 + "==" + package2, package1.equals(package2));
	}
	
	@Test
	public void testEqualsForTrivialPackagesWithDifferentVersionRanges() throws InvalidAttributeException {
		String packageName = "package.name";
		ImportedPackage package1 = instantiatePackage(packageName, "[1.0.0, 4.4.4)");
		ImportedPackage package2 = instantiatePackage(packageName, "[1.0.0, 4.4.2)");
		assertFalse("Unexpectedly reported as equal" + package1 + "==" + package2, package1.equals(package2));
	}


	@Test
	public void testEqualsForEqualPackagesWithDifferentAttributes() throws InvalidAttributeException {
		String packageName = "package.name";
		String version = "1.0.0";
		ImportedPackage package1 = instantiatePackage(packageName, version, "att=something");
		ImportedPackage package2 = instantiatePackage(packageName, version, "att=something.else");
		assertFalse("Unexpectedly reported as equal" + package1 + "==" + package2, package1.equals(package2));
	}


	private ImportedPackage instantiatePackage(String packageName,
			String version, String ... attributes) throws InvalidAttributeException {
		Map<String, String> generatedAttributes = new HashMap<String, String>();
		generatedAttributes.put(Constants.VERSION_ATTRIBUTE, version);
		for (String att : attributes)
		{
			String[] bits = att.split("=");
			generatedAttributes.put(bits[0], bits[1]);
		}
		return new ImportedPackageImpl(packageName, generatedAttributes);
	}


}
