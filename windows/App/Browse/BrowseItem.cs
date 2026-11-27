using Microsoft.UI.Xaml;
using PocketKeePass.Core;

namespace PocketKeePass.Browse;

/// <summary>
/// One row of the browse list: a group or an entry. Groups and entries are addressed by UUID,
/// never by name (KeePass allows duplicate names). Carries nothing protected: lists never do.
/// </summary>
public sealed class BrowseItem
{
    private BrowseItem(string? groupId, string? entryId, string title, string subtitle, string glyph)
    {
        GroupId = groupId;
        EntryId = entryId;
        Title = title;
        Subtitle = subtitle;
        Glyph = glyph;
    }

    public string? GroupId { get; }
    public string? EntryId { get; }
    public string Title { get; }
    public string Subtitle { get; }
    public string Glyph { get; }
    public Visibility SubtitleVisibility => Subtitle.Length == 0 ? Visibility.Collapsed : Visibility.Visible;

    // Segoe Fluent Icons. KeePass's own icons come in a later change.
    private const string FolderGlyph = "";
    private const string RecycleBinGlyph = "";
    private const string KeyGlyph = "";

    public static BrowseItem Group(GroupItem group) => new(
        group.Id, null,
        group.Name.Length == 0 ? Strings.Get("UntitledGroup") : group.Name,
        "",
        group.IsRecycleBin ? RecycleBinGlyph : FolderGlyph);

    /// <param name="groupPath">For search results: the groups the entry is in.</param>
    public static BrowseItem Entry(EntryItem entry, string[]? groupPath = null)
    {
        var details = new List<string>();
        if (entry.Username.Length > 0) details.Add(entry.Username);
        if (groupPath is { Length: > 0 }) details.Add(string.Join(" › ", groupPath));
        return new(
            null, entry.Id,
            entry.Title.Length == 0 ? Strings.Get("UntitledEntry") : entry.Title,
            string.Join(" · ", details),
            KeyGlyph);
    }

    /// <summary>What screen readers and UI Automation call the row.</summary>
    public override string ToString() => Title;
}

/// <summary>A step in the breadcrumb bar: a group from the root down to the one shown.</summary>
public sealed record Crumb(string Id, string Name)
{
    public override string ToString() => Name;
}
