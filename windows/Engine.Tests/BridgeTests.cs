using System.Reflection;
using System.Text;
using PocketKeePass.Core;

namespace PocketKeePass.Engine.Tests;

/// <summary>
/// The engine through the generated C# bindings, on the fixtures in core/tests/fixtures (their
/// passwords are in that folder's README).
/// </summary>
[TestClass]
public sealed class BridgeTests
{
    private static readonly string Fixtures = typeof(BridgeTests).Assembly
        .GetCustomAttributes<AssemblyMetadataAttribute>()
        .Single(a => a.Key == "Fixtures").Value!;

    private static byte[] Utf8(string text) => Encoding.UTF8.GetBytes(text);

    private static UnlockedDatabase Open(string name) =>
        UnlockedDatabase.Open(File.ReadAllBytes(Path.Combine(Fixtures, name)), Utf8("test-password"), null);

    /// An entry's UUID as the bridge spells it: 32 hex digits, here all the same byte.
    private static string Id(string hexByte) => string.Concat(Enumerable.Repeat(hexByte, 16));

    [TestMethod]
    public void GroupsArriveInFileOrderWithTheRecycleBinLast()
    {
        using var db = Open("browse.kdbx");
        var root = db.ListGroup(null);
        Assert.AreEqual("Root", root.Group.Name);
        CollectionAssert.AreEqual(new[] { "Work", "Banking", "Archive", "Recycle Bin" },
            root.Groups.Select(g => g.Name).ToArray());
    }

    [TestMethod]
    public void ProtectedValuesComeOneAtATimeAsBytes()
    {
        using var db = Open("browse.kdbx");
        var bank = db.Entry(Id("08"));
        Assert.AreEqual("Crédit Union", bank.Title);
        var username = bank.Fields.Single(f => f.Key == "UserName");
        Assert.IsTrue(username.IsProtected);
        Assert.AreEqual("", username.Value);
        CollectionAssert.AreEqual(Utf8("jo-secret-user"), db.RevealField(bank.Id, "UserName"));
        Assert.ThrowsExactly<KeePassException.NotFound>(() => db.RevealField(bank.Id, "Nope"));
    }

    [TestMethod]
    public void AutoTypeStepsNameFieldsWithoutTheirValues()
    {
        using var db = Open("browse.kdbx");
        var steps = db.AutoType(Id("08"));
        Assert.AreEqual(4, steps.Length);
        Assert.AreEqual(new AutoTypeStep.Field("UserName"), steps[0]);
        Assert.AreEqual(new AutoTypeStep.Press(AutoTypeKey.Tab, 1), steps[1]);
        Assert.AreEqual(new AutoTypeStep.Field("Password"), steps[2]);
        Assert.AreEqual(new AutoTypeStep.Press(AutoTypeKey.Enter, 1), steps[3]);
    }

    [TestMethod]
    public void AWrongPasswordIsItsOwnErrorWithTheEnginesSentence()
    {
        var file = File.ReadAllBytes(Path.Combine(Fixtures, "browse.kdbx"));
        var error = Assert.ThrowsExactly<KeePassException.WrongKey>(
            () => UnlockedDatabase.Open(file, Utf8("wrong"), null));
        Assert.AreEqual("The password or key file is incorrect.", error.Message);
    }

    [TestMethod]
    public void ALockedDatabaseRefusesEverything()
    {
        using var db = Open("browse.kdbx");
        db.Lock();
        Assert.ThrowsExactly<KeePassException.Locked>(() => db.ListGroup(null));
    }
}
