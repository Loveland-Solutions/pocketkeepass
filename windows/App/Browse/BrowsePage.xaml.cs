using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using PocketKeePass.Core;
using Windows.System;

namespace PocketKeePass.Browse;

/// <summary>
/// Groups and entries, as Android's BrowseScreen: one group at a time in file order (the engine
/// puts the recycle bin last), a breadcrumb bar for the way back up, and search over the whole
/// database. Engine reads run off the UI thread, and only the latest one's result is shown.
/// </summary>
public sealed partial class BrowsePage : Page
{
    /// <summary>The group shown when not searching; null for the root.</summary>
    private string? groupId;
    private string? parentId;
    /// <summary>Goes up with every read, so a slower earlier read never replaces a newer one.</summary>
    private int generation;

    public BrowsePage()
    {
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        await ShowGroup(null);
    }

    private async Task ShowGroup(string? id)
    {
        var database = Session.Shared.Database;
        if (database == null) return;
        var read = ++generation;
        GroupListing listing;
        try
        {
            listing = await Task.Run(() => database.ListGroup(id));
        }
        catch (KeePassException.NotFound) when (id != null)
        {
            await ShowGroup(null);
            return;
        }
        catch (KeePassException.Locked)
        {
            return;
        }
        if (read != generation) return;

        groupId = listing.IsRoot ? null : listing.Group.Id;
        parentId = listing.Parents.LastOrDefault()?.Id;
        UpButton.IsEnabled = !listing.IsRoot;
        Breadcrumbs.ItemsSource = listing.Parents.Append(listing.Group)
            .Select((g, i) => new Crumb(g.Id, CrumbName(g, i == 0)))
            .ToList();
        var items = listing.Groups.Select(BrowseItem.Group)
            .Concat(listing.Entries.Select(e => BrowseItem.Entry(e)))
            .ToList();
        Show(items, "GroupEmpty");
    }

    /// <summary>The root group is often named after the database, or not at all.</summary>
    private static string CrumbName(PocketKeePass.Core.GroupItem group, bool isRoot)
    {
        if (group.Name.Length > 0) return group.Name;
        return isRoot ? Path.GetFileNameWithoutExtension(Session.Shared.Path ?? "") : Strings.Get("UntitledGroup");
    }

    private async Task Search(string query)
    {
        var database = Session.Shared.Database;
        if (database == null) return;
        var read = ++generation;
        SearchResult[] results;
        try
        {
            results = await Task.Run(() => database.Search(query));
        }
        catch (KeePassException.Locked)
        {
            return;
        }
        if (read != generation) return;
        Show(results.Select(r => BrowseItem.Entry(r.Entry, r.GroupPath)).ToList(), "SearchNoResults");
    }

    private void Show(List<BrowseItem> items, string emptyMessageKey)
    {
        Items.ItemsSource = items;
        EmptyText.Text = items.Count == 0 ? Strings.Get(emptyMessageKey) : "";
        EmptyText.Visibility = items.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private bool Searching => SearchBox.Text.Trim().Length > 0;

    // The handlers are async void, so an unexpected exception reaches App.UnhandledException
    // rather than vanishing with a discarded task.
    private async void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        var query = SearchBox.Text.Trim();
        var searching = query.Length > 0;
        Breadcrumbs.Visibility = searching ? Visibility.Collapsed : Visibility.Visible;
        UpButton.Visibility = searching ? Visibility.Collapsed : Visibility.Visible;
        await (searching ? Search(query) : ShowGroup(groupId));
    }

    private void SearchBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Escape || SearchBox.Text.Length == 0) return;
        e.Handled = true;
        SearchBox.Text = "";
    }

    private async void Items_ItemClick(object sender, ItemClickEventArgs e)
    {
        // Entries open in their own screen in a later change.
        if (e.ClickedItem is BrowseItem { GroupId: { } id })
        {
            SearchBox.Text = "";
            await ShowGroup(id);
        }
    }

    private async void Breadcrumbs_ItemClicked(BreadcrumbBar sender, BreadcrumbBarItemClickedEventArgs args)
    {
        if (args.Item is Crumb crumb) await ShowGroup(crumb.Id);
    }

    private async Task Up()
    {
        if (Searching || groupId == null) return;
        await ShowGroup(parentId);
    }

    private async void Up_Click(object sender, RoutedEventArgs e) => await Up();

    private async void Up_Invoked(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        args.Handled = true;
        await Up();
    }

    private void Find_Invoked(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        args.Handled = true;
        SearchBox.Focus(FocusState.Keyboard);
        SearchBox.SelectAll();
    }

    private void Lock_Click(object sender, RoutedEventArgs e) => Session.Shared.Lock();

    private void Lock_Invoked(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        args.Handled = true;
        Session.Shared.Lock();
    }
}
